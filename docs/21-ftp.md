---
title: "Transferring Files"
nav_order: 21
lede: "A client and server that move files over a socket one frame at a time, then learn to serve many clients and to shut down cleanly."
---

Two programs on two machines share nothing. Nothing passes between them
but a stream of bytes with no structure of any kind, and every idea we take for granted inside one program, such as "this is a
name", "this is where the data ends", or "it is your turn to speak", has
to be rebuilt on top of that stream by agreement between the two sides.

This chapter builds a tool that copies a file from one program to
another over a network connection. The first version sends one whole
file and stores it. The second sends it in chunks so that files larger
than memory work too. The third serves many clients at once and shuts
down without leaving anything dangling. The tool is small, but the ideas
it needs are the ones every network program needs: **framing** (how to
find the edges of a message in a stream), **blocking I/O** (what a program
does while it waits for the network), buffering, distrust of anything
that arrives over the wire, concurrency, and the surprisingly hard
problem of stopping.

## Sockets and streams

A **socket** is the operating system's name for one end of a network
connection. The JDK wraps it in two classes. `java.net.ServerSocket`
listens on a port and waits for someone to connect; each time someone
does, its `accept` method hands back a `java.net.Socket` for that one
conversation. A client makes its own `Socket` by naming the host and port
it wants to reach. Once both sides have a `Socket`, each one has an
`InputStream` to read what the other side wrote and an `OutputStream` to
write to.

That is the chapter's first Java interop, so a word about how it looks. `(Socket. host port)` calls a Java constructor; the trailing
dot means "new". `(.getInputStream sock)` calls a method on an object;
the leading dot means "method call". The odd-looking `^Socket` in front
of an argument name is a **type hint**: it tells the compiler what class
to expect, so that it can find the method at compile time instead of
searching for it by name on every call. Hints are optional, but on a
function that is called once per byte they make a real difference.

Reading and writing raw bytes is tedious, so we wrap each stream. A
`DataInputStream` knows how to read an `int` or a `long` from four or
eight bytes; a `DataOutputStream` knows how to write one. We also put a
buffer under each of them, because every unbuffered write to a socket is
a system call, and we are going to be doing a lot of small ones:

```clojure
(defn data-in
  "A buffered DataInputStream reading from `sock`."
  ^DataInputStream [^Socket sock]
  (DataInputStream. (BufferedInputStream. (.getInputStream sock))))

(defn data-out
  "A buffered DataOutputStream writing to `sock`. Nothing reaches the
   network until it is flushed."
  ^DataOutputStream [^Socket sock]
  (DataOutputStream. (BufferedOutputStream. (.getOutputStream sock))))
```

The docstring on `data-out` is a warning as much as a description. A
buffered stream holds on to what we write until the buffer fills up or
we call `.flush`. If we write a request and then sit waiting for the
reply without flushing, the request is still in our buffer, the server
is waiting for it, and both sides wait forever. Almost everyone who
writes network code makes this mistake exactly once.

## Framing: where does one message end?

An `InputStream` delivers bytes in whatever lumps the network happens to
deliver them. A message that was written in one call may arrive in
three, or three messages may arrive in one. The stream has no notion of
a message at all, so if we want messages we have to mark their edges
ourselves. This is called framing, and there are three common ways to
do it:

- **Fixed size.** Every message is exactly N bytes. Simple, but useless
  for anything of variable length.
- **Delimiter.** A message ends at a special byte such as a newline.
  Fine for text, but now the delimiter can never appear inside a
  message, so binary data has to be escaped.
- **Length prefix.** Each message starts with a count of how many bytes
  follow. The reader reads the count, then reads exactly that many
  bytes, and never has to inspect them.

We use the length prefix. A **frame** is a 4-byte count followed by the
bytes, and the only other thing we need to agree on is which way round
the four bytes of the count go. `DataOutputStream` writes the most
significant byte first, which is the convention nearly every network
protocol uses, so we let it decide:

```clojure
(def max-frame
  "The largest frame we are willing to read: 16 MiB."
  (* 16 1024 1024))

(defn write-frame
  "Write `bs` to `out` as one frame: a 4-byte length, then the bytes."
  [^DataOutputStream out ^bytes bs]
  (.writeInt out (alength bs))
  (.write out bs 0 (alength bs)))

(defn read-frame
  "Read one frame from `in` and return its bytes. Throws if the length
   is negative or larger than `max-frame`, which means the stream is
   corrupt or the sender is not to be trusted."
  ^bytes [^DataInputStream in]
  (let [n (.readInt in)]
    (when (or (neg? n) (> n max-frame))
      (throw (ex-info "bad frame length" {:length n})))
    (let [buf (byte-array n)]
      (.readFully in buf)
      buf)))
```

```clojure
(let [bytes (java.io.ByteArrayOutputStream.)]
  (write-frame (java.io.DataOutputStream. bytes) (.getBytes "hello" "UTF-8"))
  (vec (.toByteArray bytes)))
;; => [0 0 0 5 104 101 108 108 111]
```

Two details of `read-frame` matter more than they look. The first is
`.readFully`, which keeps reading until it has filled the whole array
or the stream ends. The plain `.read` method is allowed to return fewer
bytes than you asked for, and usually does when the data is still
arriving; a program that calls `.read` once and assumes it got
everything works on a quiet machine and fails under load. The second is
the check on `n`. The length arrives over the network from a program
that could be buggy, malicious, or simply speaking a different
protocol. Four bytes of garbage make a fine
negative number, or a request to allocate two gigabytes. The rule is:
never trust a value that came over the wire until you have checked it.

Strings are frames whose bytes happen to be UTF-8:

```clojure
(defn write-str
  "Write the string `s` as a UTF-8 frame."
  [out ^String s]
  (write-frame out (.getBytes s "UTF-8")))

(defn read-str
  "Read one frame and decode it as UTF-8."
  [in]
  (String. (read-frame in) "UTF-8"))
```

Naming the encoding is not fussiness. `(.getBytes s)` with no argument
uses whatever the current machine's default is, and the machine at the
other end may have a different default. A protocol that depends on both
ends having the same locale is not a protocol.

Framing is the sort of thing that can be tested without any network at
all, and the tests in `test/sdx/ftp/simple_test.clj` start that way,
writing to a byte array and reading back from it:

```clojure
(deftest frames-round-trip
  (let [payload (.getBytes "hello" "UTF-8")
        wire (frame-bytes #(ftp/write-frame % payload))]
    (testing "a frame is a 4-byte length followed by the bytes"
      (is (= [0 0 0 5 104 101 108 108 111] (vec wire))))
    (is (= (vec payload) (vec (ftp/read-frame (data-input wire)))))))
```

The guard on the length gets a test of its own, including a length that
promises more bytes than actually follow:

```clojure
(deftest bad-frame-lengths-are-refused
  (testing "a negative length"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bad frame length"
                          (ftp/read-frame (data-input (byte-array [-1 -1 -1 -1]))))))
  (testing "a length with no bytes behind it"
    (is (thrown? EOFException
                 (ftp/read-frame (data-input (byte-array [0 0 0 9 1 2])))))))
```

## The client

With frames in hand the protocol for sending a file almost writes
itself. The client sends the file's name, then the file's bytes, and
waits for the server to say how many bytes it stored. The server's
reply is a single 8-byte `long` rather than a frame, since its length
is known in advance.

```clojure
(defn send-file
  "Send the file at `path` to the server at `host`:`port`. Returns the
   number of bytes the server says it stored."
  [^String host port path]
  (let [f (io/file path)]
    (with-open [sock (Socket. host (int port))]
      (let [in (data-in sock)
            out (data-out sock)]
        (write-str out (.getName f))
        (write-frame out (Files/readAllBytes (.toPath f)))
        (.flush out)
        (.readLong in)))))
```

`with-open` is a macro that binds a name to something closeable, runs
the body, and calls `.close` on it afterwards inside a `finally`, so
the socket is closed whether the body returns normally or throws. We
open only the socket, not the two streams: closing a socket closes
everything attached to it, and closing the streams separately raises
awkward questions about which one goes first.

The `.flush` before `.readLong` is the one discussed above. Then the
client blocks. `.readLong` does not return until eight bytes have
arrived, and until they do the thread that called it is doing nothing
at all. This is **blocking I/O**: the simplest model there is, since
the program reads like a conversation, at the cost that one thread can
hold only one conversation at a time. We will feel that cost in the
third stage.

## The server

The server's first job is not to read the file. It is to decide
whether to believe the name it was sent. The client we wrote sends a
bare file name, but nothing stops a different client from sending
`../../home/someone/.bashrc`, and a server that joined that to its
storage directory would write wherever it was told. The rule from
`read-frame` applies again:

```clojure
(defn safe-name
  "Check that a file name sent by a client is a plain name and not a
   path. Returns the name, or throws."
  [^String name]
  (if (or (str/blank? name)
          (#{"." ".."} name)
          (re-find #"[/\\\u0000]" name))
    (throw (ex-info "unsafe file name" {:name name}))
    name))
```

```clojure
(safe-name "notes.txt")     ;; => "notes.txt"
(safe-name "../notes.txt")  ;; throws: unsafe file name {:name "../notes.txt"}
```

We could instead strip the directory part and keep the base name, and
many servers do. Refusing is simpler to reason about: a name is a name,
and anything else is a protocol violation. The regular expression also
rejects a NUL byte, which some filesystems treat as the end of the
string and which has been the root of more than one security hole.

Receiving a file is the client in reverse: read the name, read the
bytes, write them to disk, say how many there were.

```clojure
(defn receive-file
  "Read one file from the client on `sock` and store it in `dir`. Replies
   with the number of bytes stored and returns the stored file."
  [^Socket sock dir]
  (with-open [sock sock]
    (let [in (data-in sock)
          out (data-out sock)
          name (safe-name (read-str in))
          data (read-frame in)
          target (io/file dir name)]
      (with-open [fout (io/output-stream target)]
        (.write fout data))
      (.writeLong out (alength data))
      (.flush out)
      target)))
```

The server has to get the socket from somewhere, and the somewhere is
an **accept loop**: block in `.accept` until a client connects, deal
with that client, go back to blocking.

```clojure
(defn serve
  "Accept connections on `server` one after another until it is closed,
   calling `(handle socket)` for each. A connection that fails is dropped
   and the loop carries on."
  [^ServerSocket server handle]
  (while (not (.isClosed server))
    (try
      (handle (.accept server))
      (catch Exception _ nil))))
```

Notice that `serve` does not know what a file is. It takes a function
and calls it with each socket. The two jobs, accepting connections and
talking over one, have nothing to do with each other, and the next two
stages will reuse the loop unchanged. The `try` around the call is what
keeps a bad client from taking the server down with it: if `safe-name`
throws, this client's socket is closed by its `with-open`, the exception
is swallowed, and the loop goes round again. Swallowing exceptions
silently is not good manners, and stage 3 will fix it, but the loop has
to survive before it can do anything else.

How does the loop ever end? `.accept` blocks, so nobody can set a flag
that it will check. Instead, closing the `ServerSocket` from another
thread makes the blocked `.accept` throw. The `catch` catches that too,
the `while` condition sees that the server is closed, and the loop
exits. This is the standard trick for interrupting blocking I/O, and it
is worth knowing because nothing else works.

```clojure
(defn start-server
  "Start a server that stores received files in `dir`, listening on the
   loopback address. Port 0, the default, lets the operating system pick
   a free port. Returns a map with the :port actually chosen and a :stop
   function that shuts the server down and waits for it to finish."
  ([dir] (start-server dir 0))
  ([dir port]
   (.mkdirs (io/file dir))
   (let [server (ServerSocket. (int port) 50 (InetAddress/getLoopbackAddress))
         worker (future (serve server #(receive-file % dir)))]
     {:port (.getLocalPort server)
      :stop (fn []
              (.close server)
              @worker)})))
```

Three decisions are packed into `start-server`. The server binds to the
**loopback address**, 127.0.0.1, which only programs on the same machine
can reach, so that running the tests does not open a door to the
network. It asks for port 0, which tells the operating system to pick
any free port and lets us ask afterwards which one it chose, so the
tests never collide with each other or with whatever else is using a
port. And it runs the accept loop in a `future`.

A **future** runs its body on another thread from a pool that Clojure
manages, and immediately returns a handle. Dereferencing the handle with
`@` (or `deref`) blocks until the body has finished and then returns its
value, or rethrows if the body threw. Here the body is the accept loop,
which does not finish until the server is closed, so `@worker` in
`:stop` means "wait for the loop to notice it has been closed and
exit". The map holds a `:stop` function rather than the socket, so
callers can stop the server without knowing how it was started; a
function that remembers `server` and `worker` is a closure (see
[the chapter on closures](../08-closures/)).

The test for all this starts a server, sends a file, and reads it back
from the server's directory. The temporary directory comes from a small
helper namespace, `sdx.ftp.helpers`, which also deletes it afterwards:

```clojure
(deftest sending-a-file
  (h/with-temp-dir
    (fn [dir]
      (let [src (h/write-file! (io/file dir "src" "hello.txt")
                               (.getBytes "hello, world" "UTF-8"))
            dst (io/file dir "dst")
            {:keys [port stop]} (ftp/start-server dst)]
        (try
          (is (= 12 (ftp/send-file "127.0.0.1" port src)))
          (is (= "hello, world" (slurp (io/file dst "hello.txt"))))
          (testing "an empty file is still a file"
            (let [empty (h/write-file! (io/file dir "src" "empty") (byte-array 0))]
              (is (= 0 (ftp/send-file "127.0.0.1" port empty)))
              (is (.exists (io/file dst "empty")))))
          (testing "every byte value survives the trip"
            (let [data (byte-array (map unchecked-byte (range 256)))
                  f (h/write-file! (io/file dir "src" "all.bin") data)]
              (is (= 256 (ftp/send-file "127.0.0.1" port f)))
              (is (= (vec data) (vec (h/read-file (io/file dst "all.bin")))))))
          (finally
            (stop)))))))
```

The `finally` is not optional. A test that fails halfway through would
otherwise leave a server thread running for the rest of the test run,
and stray threads make unrelated tests fail in ways nobody can
reproduce. A second
test in the same file sends `../escaped.txt` as a name, checks that the
server hangs up, that nothing was written, and that the next, honest
client is served as if nothing had happened.

## Files that do not fit in memory

Stage 1 reads the whole file into a byte array, sends it as one frame,
and the server reads the whole frame into another byte array before
writing any of it. That caps the file at `max-frame` and, worse, at
whatever memory the two processes have to spare. A file transfer tool
that cannot transfer a large file is not much of a tool.

The fix is to stop treating the file as a frame. The name stays a
frame, but the body becomes an 8-byte length followed by exactly that
many bytes, and both sides move those bytes through a fixed-size buffer
a chunk at a time. The function that does the moving is the heart of
stage 2, in `sdx.ftp.chunked`:

```clojure
(def buffer-size
  "How many bytes travel in one chunk. Any size works; this one is typical."
  4096)

(defn copy-n
  "Copy exactly `n` bytes from `in` to `out` through a buffer of
   `buffer-size` bytes. Returns `n`. Throws if the input ends early."
  [^InputStream in ^OutputStream out n]
  (let [buf (byte-array buffer-size)]
    (loop [remaining n]
      (if (zero? remaining)
        n
        (let [want (int (min remaining buffer-size))
              got (.read in buf 0 want)]
          (when (neg? got)
            (throw (ex-info "stream ended early"
                            {:expected n :missing remaining})))
          (.write out buf 0 got)
          (recur (- remaining got)))))))
```

```clojure
(let [in (java.io.ByteArrayInputStream. (byte-array (range 10)))
      out (java.io.ByteArrayOutputStream.)]
  [(copy-n in out 7) (vec (.toByteArray out)) (.available in)])
;; => [7 [0 1 2 3 4 5 6] 3]
```

This is a place where a mutable byte array is doing real work, so it
is worth saying why that is fine. The array never
leaves the function; nothing else can see it; it is overwritten on every
turn of the loop and forgotten when the loop ends. Immutability is a
tool for keeping programs easy to reason about, and a buffer that is
private to one loop is already easy to reason about.

The loop itself has the shape every chunked copy has, and it is shaped
by the same fact we met in `read-frame`: `.read` returns however many
bytes were convenient, between one and `want`, or -1 at the end of the
stream. So we ask for at most `buffer-size` and never more than we
still need, write however much we actually got, and subtract that from
the remainder. Never asking for more than we still need stops the
server from swallowing bytes that belong to whatever comes next on the
connection. Ending early is an error, not a short file: a half-written
file that looks complete is worse than no file.

The client opens the file as a stream instead of reading it, and
reports its length up front:

```clojure
(defn send-file
  "Send the file at `path` to `host`:`port` in chunks. Returns the number
   of bytes the server says it stored."
  [^String host port path]
  (let [f (io/file path)]
    (with-open [sock (Socket. host (int port))
                fin (io/input-stream f)]
      (let [in (data-in sock)
            out (data-out sock)]
        (write-str out (.getName f))
        (.writeLong out (.length f))
        (copy-n fin out (.length f))
        (.flush out)
        (.readLong in)))))
```

And the server streams straight from the socket into the file:

```clojure
(defn receive-file
  "Read one file from the client on `sock` in chunks and store it in
   `dir`. Replies with the number of bytes stored and returns the file."
  [^Socket sock dir]
  (with-open [sock sock]
    (let [in (data-in sock)
          out (data-out sock)
          name (safe-name (read-str in))
          size (.readLong in)
          target (io/file dir name)]
      (when (neg? size)
        (throw (ex-info "bad file size" {:size size})))
      (with-open [fout (io/output-stream target)]
        (copy-n in fout size))
      (.writeLong out size)
      (.flush out)
      target)))
```

At no point does either side hold more than 4096 bytes of the file. The
accept loop is unchanged, which is the payoff for making `serve` take a
function: stage 2's `start-server` is stage 1's with a different
argument.

```clojure
(defn start-server
  "Start a chunked server storing files in `dir`. The accept loop is the
   one from stage 1; only what happens on each connection has changed."
  ([dir] (start-server dir 0))
  ([dir port]
   (.mkdirs (io/file dir))
   (let [server (ServerSocket. (int port) 50 (InetAddress/getLoopbackAddress))
         worker (future (simple/serve server #(receive-file % dir)))]
     {:port (.getLocalPort server)
      :stop (fn []
              (.close server)
              @worker)})))
```

The tests in `test/sdx/ftp/chunked_test.clj` send files of 0, 1, 4096,
4097 and 100000 bytes. The sizes are chosen to land exactly on a chunk
boundary, one byte past it, and well beyond it, because off-by-one
errors in copy loops hide precisely at those edges.

## Many clients at once

Stage 2 still has one accept loop and one thread, so it serves clients
strictly in turn. If a client connects, sends its name frame, and then
goes quiet, `receive-file` sits in `.readLong` forever, and every other
client that connects after it waits in the operating system's queue,
also forever. Two things are missing: a way to serve clients at the
same time, and a way to give up on a client that has stopped talking.

Both arrive in `sdx.ftp.concurrent`, and both need the server to keep
some state. The accept loop hands each connection to its own `future`,
and so it has to remember which connections are open in order to close
them when it stops. That memory is shared between the accept thread,
which adds to it, and every handler thread, which removes from it, so
it has to be an **atom**: a reference to an immutable value that can be
swapped for a new value atomically. `(swap! connections assoc sock fut)`
applies `assoc` to the current value and installs the result, and if
two threads do that at the same moment, one of them simply retries.
There is no lock to forget.

```clojure
(defn report-error
  "The default error reporter: one line on standard error."
  [^Throwable e]
  (binding [*out* *err*]
    (println "client failed:" (.getMessage e))))

(defn serve
  "Accept connections on `server` until it is closed. Each connection is
   handled by `(handle socket)` on its own thread and dropped if it is
   silent for `timeout-ms`. `connections` is an atom holding a map from
   open socket to the future handling it; `on-error` is called with any
   exception a handler throws."
  [^ServerSocket server handle {:keys [connections timeout-ms on-error]}]
  (while (not (.isClosed server))
    (when-let [^Socket sock (try (.accept server)
                                 (catch IOException _ nil))]
      (.setSoTimeout sock timeout-ms)
      (let [fut (future
                  (try
                    (handle sock)
                    (catch Exception e (on-error e))
                    (finally
                      (.close sock)
                      (swap! connections dissoc sock))))]
        (swap! connections assoc sock fut)))))
```

`.setSoTimeout` is the answer to the silent client. It makes every read
on that socket throw a `SocketTimeoutException` if no bytes arrive
within the given time, which turns an eternal wait into an ordinary
error that the `catch` reports and the `finally` cleans up after. The
error no longer vanishes, either: it goes to `on-error`, which is a
parameter. By default that prints a line to standard error; a test can
pass a function that records the exception instead.
Taking the reporter as an argument is a small instance of a large idea:
anything a function does to the outside world is easier to test if the
function is told how to do it. `binding` sets `*out*`, the stream `println` writes to, for the
duration of its body and only on this thread; it is how Clojure gives a
global a temporary, thread-local value.

There is a small race between the `finally` removing the socket from
the map and the `swap!` that adds it, if a client finishes before the
accept thread gets round to the `assoc`. The stale entry does no harm:
closing a closed socket is a no-op, and waiting on a finished future
returns at once. Sometimes the right response to a race is to make its
outcomes equivalent instead of preventing it.

```clojure
(defn start-server
  "Start a concurrent server storing files in `dir`. Options: :port
   (default 0), :timeout-ms (default 10000) and :on-error (default:
   print to standard error). Returns a map with the :port chosen, the
   :connections atom and a :stop function."
  ([dir] (start-server dir {}))
  ([dir {:keys [port timeout-ms on-error]
         :or {port 0 timeout-ms 10000 on-error report-error}}]
   (.mkdirs (io/file dir))
   (let [server (ServerSocket. (int port) 50 (InetAddress/getLoopbackAddress))
         connections (atom {})
         worker (future (serve server #(chunked/receive-file % dir)
                               {:connections connections
                                :timeout-ms timeout-ms
                                :on-error on-error}))]
     {:port (.getLocalPort server)
      :connections connections
      :stop (fn []
              (.close server)
              @worker
              (doseq [[^Socket sock fut] @connections]
                (.close sock)
                (deref fut 1000 nil)))})))
```

Stopping is now a three-step affair, and the order matters. Closing the
server socket stops new connections from arriving. Waiting on `worker`
makes sure the accept loop has really exited, so nothing will be added
to `connections` after we read it. Then every open socket is closed,
which makes any handler blocked in a read throw and finish, and we wait
for each handler, with a one-second limit in case one is stuck somewhere
other than a read. `(deref fut 1000 nil)` is `deref` with a timeout and
a value to return if time runs out. When `stop` returns, there is
nothing left running, which is exactly the promise a test needs.

The test for the timeout opens a socket, sends a name, and then does
nothing. The exception the server reports is delivered to a **promise**,
a one-shot box that `deliver` fills and `deref` waits on, so the test
blocks until the server has given up and not a moment longer:

```clojure
(deftest a-stalled-client-is-cut-off
  (h/with-temp-dir
    (fn [dir]
      (let [failure (promise)
            {:keys [port stop]} (ftp/start-server (io/file dir "dst")
                                                  {:timeout-ms 200
                                                   :on-error #(deliver failure %)})]
        (try
          (with-open [sock (Socket. "127.0.0.1" (int port))]
            (let [out (simple/data-out sock)]
              (simple/write-str out "stalled.txt")
              (.flush out)
              (testing "the server gives up on us after the timeout"
                (is (instance? SocketTimeoutException (deref failure 5000 ::no-error))))))
          (testing "and is still there for the next client"
            (let [f (h/write-file! (io/file dir "src" "ok.txt") (.getBytes "hello"))]
              (is (= 5 (chunked/send-file "127.0.0.1" port f)))))
          (finally
            (stop)))))))
```

Another test in `test/sdx/ftp/concurrent_test.clj` sends eight files
from eight futures at once and checks that all eight arrive intact, and
a third connects, waits until the server has accepted it, calls `stop`,
and checks that the server closed the connection from its side. Each of
these would have hung forever against stage 2.

## A command-line tool

All that remains is a way to run it from a shell. `sdx.ftp.main` takes a
subcommand: `serve` starts the concurrent server and blocks, `send`
uses the chunked client.

```clojure
(defn -main
  "Run the server (`serve`) or send files to one (`send`)."
  [& [command & args]]
  (case command
    "serve" (let [[dir port] args
                  server (concurrent/start-server
                          dir {:port (if port (Long/parseLong port) 0)})]
              (println "storing files in" dir "on port" (:port server))
              ;; A promise nobody delivers: block until the process is killed.
              @(promise))
    "send" (let [[host port & files] args]
             (doseq [f files]
               (println f "->" (chunked/send-file host (Long/parseLong port) f)
                        "bytes")))
    (usage)))
```

In one terminal:

```
$ clojure -M -m sdx.ftp.main serve inbox 2121
storing files in inbox on port 2121
```

And in another:

```
$ clojure -M -m sdx.ftp.main send 127.0.0.1 2121 note.txt index.html
note.txt -> 10 bytes
index.html -> 12 bytes
```

The server's main thread has nothing to do once the accept loop is
running on its future, so it blocks on a promise nobody will deliver
and waits to be killed. A real server would catch the termination
signal and call `:stop`, which is one of the exercises.
`test/sdx/ftp/main_test.clj` runs the `send` subcommand against a
server started in the test and checks what it prints, cheap insurance
against the argument parsing drifting away from the functions under it.

## Summary

- A socket delivers bytes with no structure. Messages exist only if
  both sides agree where their edges are, and a length prefix is the
  most robust way to agree.
- `read` returns however many bytes are convenient, not how many you
  asked for. Use `readFully` when you know the count, and loop when you
  are copying.
- Buffered output stays in the buffer until it is flushed. A request
  that is never flushed is a conversation that never starts.
- Nothing that arrives over the wire can be trusted: not lengths, not
  names, not sizes. Check at the boundary and reject rather than repair.
- Blocking I/O is simple to write but occupies a thread per
  conversation. Futures give each conversation its own thread; socket
  timeouts stop a silent peer from occupying it forever.
- The only way to interrupt a thread blocked in `accept` or `read` is to
  close the socket it is blocked on, so clean shutdown means closing
  sockets in the right order and waiting for the threads to notice.
- Separating the accept loop from what happens on one connection lets
  the same loop serve every stage of the tool, and the next chapter's
  web server too.

## Exercises

1. **Error replies.** The server's only answer to a bad request is to
   hang up, which the client sees as an `EOFException`. Change the reply
   to a status byte followed by the count, so that the client can tell
   "unsafe name" from "connection lost". What else has to change in
   `send-file`?

2. **Checksums.** After the transfer, have the server reply with the
   SHA-256 of what it stored (see [finding duplicate files](../03-dupes/))
   and have the client compare it with its own. Does this catch anything
   that the byte count does not?

3. **Fetching.** Add a `get` command that asks the server for a file by
   name and stores it locally. How much of `copy-n` and `safe-name` can
   you reuse unchanged?

4. **Lying about length.** In stage 2 a client can claim a size of
   2^62 bytes and then send ten. Trace what happens on the server, then
   add a limit, and decide whether to check it before or after creating
   the file.

5. **A bounded pool.** `future` uses a thread pool that grows without
   limit. Replace it with a `java.util.concurrent.Executors/newFixedThreadPool`
   of four threads. What does the fifth client see while the first four
   are busy, and is that better or worse?

6. **Graceful exit.** Register a shutdown hook with
   `(.addShutdownHook (Runtime/getRuntime) (Thread. f))` so that
   pressing Ctrl-C in the `serve` command calls `:stop` instead of
   killing the process. What happens to a transfer that is in progress?

7. **Delimited names.** Rewrite `write-str` and `read-str` to end the
   name with a newline instead of prefixing a length. Write a test with
   a file name containing a newline and watch what the server does.
   What would you have to add to make the delimiter safe?

8. **Overwriting.** Two clients sending files with the same name
   overwrite each other, and in stage 3 they may do so at the same time.
   Make the server refuse the second or rename it, and write a test that
   proves it works under concurrency.
