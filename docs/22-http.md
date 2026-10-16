---
title: "Serving Web Pages"
nav_order: 22
lede: "A web server that parses HTTP by hand, serves files without leaking the rest of the disk, and answers requests with plain functions."
---

A web browser and a web server speak a protocol called HTTP, and HTTP
is unusual among network protocols in that it is mostly text. A request
is a few lines you could type by hand; a response is a few more lines
followed by whatever was asked for. That makes it a good protocol to
build from scratch, because we can see every byte, and a good one to
build on top of, because nearly every program that talks to another
program over a network today does it with HTTP.

This chapter builds a server in three stages. The first reads a request
from a socket into a map, hands the map to a function, and writes the
map that comes back out as a response; its only function says hello.
The second replaces that function with one that serves files from a
directory, which turns out to be mostly about what not to serve. The
third notices that "a function from a request to a response" is a
complete design on its own, and builds routing, logging and error
handling as functions that wrap other functions. The accept loop, the
threads and the timeouts are the ones from [the file transfer
chapter](../21-ftp/); what is new here is everything above the socket.

## What a request looks like

When a browser asks for `http://example.com/index.html`, it opens a
connection to port 80 on `example.com` and writes something like this:

```
GET /index.html HTTP/1.1
Host: example.com
Accept: text/html

```

The first line is the **request line**: a method, a target and a
protocol version, separated by single spaces. Then come zero or more
**headers**, each a name, a colon and a value. Then a blank line, which
means the headers are over. Each line ends with the two characters
carriage return and line feed, written `\r\n`. If the request has a
**body**, such as the contents of a form, it follows the blank line, and
a `Content-Length` header says how many bytes long it is.

That last detail decides how we read the stream. Headers are text, but
the body is bytes, and a body might be an image. If we wrapped the
socket's `InputStream` in a `BufferedReader` to get lines, the reader
would decode bytes into characters and keep some of them in its own
buffer, and we could never get the body back out as bytes. So we read
lines ourselves, one byte at a time, from a `BufferedInputStream`, and
stop decoding at the blank line:

```clojure
(defn read-http-line
  "Read one line from `in`. HTTP lines end with CRLF; a bare LF is
   tolerated. Returns the line without its terminator, or nil if the
   stream ended before any bytes arrived."
  [^InputStream in]
  (let [buf (ByteArrayOutputStream.)]
    (loop []
      (let [b (.read in)]
        (cond
          (neg? b) (when (pos? (.size buf)) (.toString buf "ISO-8859-1"))
          (= b 10) (str/replace (.toString buf "ISO-8859-1") #"\r$" "")
          :else (do (.write buf b) (recur)))))))
```

The lines are decoded as ISO-8859-1, in which every byte is exactly one
character, so nothing can go wrong even if a client sends something
odd. Returning `nil` when the stream ends before any byte arrives lets
the caller tell "the client went away" from "the client sent an empty
line", which are very different things.

The request line is three fields or it is nothing:

```clojure
(defn parse-request-line
  "Split \"GET /path?q=1 HTTP/1.1\" into a map. Throws ex-info carrying
   {:status 400} if the line is not shaped like that."
  [line]
  (let [[method target version :as parts] (str/split line #" ")]
    (when (or (not= 3 (count parts))
              (not (str/starts-with? version "HTTP/")))
      (throw (ex-info (str "malformed request line: " (pr-str line))
                      {:status 400 :line line})))
    (let [[path query] (str/split target #"\?" 2)]
      {:method (keyword (str/lower-case method))
       :uri target
       :path path
       :query query
       :version version})))
```

```clojure
(parse-request-line "GET /a?b=1 HTTP/1.1")
;; => {:method :get, :uri "/a?b=1", :path "/a", :query "b=1", :version "HTTP/1.1"}
(parse-request-line "GET /")
;; throws: malformed request line: "GET /"  {:status 400, :line "GET /"}
```

The method becomes a keyword, because `:get` is what we will want to
dispatch on, and the target is split at its first `?` into a path and a
query string, which are used for different things. The exception
carries a map with `:status 400`, and that number is a convention we
will lean on throughout the chapter: an exception whose data names an
HTTP status is not a bug, it is an answer, and something further up
will turn it into a response with that status. Bad Request, 400, is
HTTP's way of saying "I could not make sense of that".

Headers follow the same pattern. Their names are case-insensitive by
the standard, so we lower-case them once at the boundary and never
think about it again:

```clojure
(defn parse-header
  "Split \"Name: value\" into [\"name\" \"value\"], lower-casing the name
   because header names are case-insensitive."
  [line]
  (let [[name value] (str/split line #":" 2)]
    (when (nil? value)
      (throw (ex-info (str "malformed header: " (pr-str line))
                      {:status 400 :line line})))
    [(str/lower-case (str/trim name)) (str/trim value)]))

(defn read-headers
  "Read header lines up to the blank line and return them as a map."
  [in]
  (loop [headers {}]
    (let [line (read-http-line in)]
      (if (str/blank? line)
        headers
        (recur (conj headers (parse-header line)))))))
```

```clojure
(parse-header "Host: example.com")  ;; => ["host" "example.com"]
```

The `2` in `(str/split line #":" 2)` limits the split to two pieces, so
a value that contains a colon, such as a time, survives. `conj` on a map
with a two-element vector adds it as a key and value, which is why
`parse-header` returns a vector rather than a map.

The body, if there is one, is exactly `Content-Length` bytes, and
reading it is the same loop as the chunked copy in the previous chapter
for the same reason: `.read` returns what it has, not what you asked
for.

```clojure
(defn read-body
  "Read the request body: exactly Content-Length bytes, or nil if the
   request has no Content-Length header."
  [^InputStream in headers]
  (when-let [length (get headers "content-length")]
    (let [n (try (Long/parseLong length)
                 (catch NumberFormatException _
                   (throw (ex-info (str "bad Content-Length: " (pr-str length))
                                   {:status 400}))))
          buf (byte-array n)]
      (loop [offset 0]
        (when (< offset n)
          (let [got (.read in buf offset (- n offset))]
            (when (neg? got)
              (throw (ex-info "body ended early" {:status 400})))
            (recur (+ offset got)))))
      buf)))

(defn parse-request
  "Read one HTTP request from `in` into a map. Returns nil if the stream
   is empty, which happens when a client connects and goes away. Throws
   ex-info carrying {:status 400} if the request is malformed."
  [in]
  (when-let [line (read-http-line in)]
    (let [request (parse-request-line line)
          headers (read-headers in)]
      (assoc request
             :headers headers
             :body (read-body in headers)))))
```

Everything so far takes an `InputStream` and nothing else, so it can be
tested with a string and no network. The tests in
`test/sdx/http/hello_test.clj` do exactly that:

```clojure
(deftest parsing-a-whole-request
  (testing "a GET with headers and no body"
    (let [request (http/parse-request
                   (stream "GET /index.html HTTP/1.1\r\nHost: localhost\r\nAccept: */*\r\n\r\n"))]
      (is (= :get (:method request)))
      (is (= "/index.html" (:path request)))
      (is (= {"host" "localhost" "accept" "*/*"} (:headers request)))
      (is (nil? (:body request)))))
  (testing "a POST reads exactly Content-Length bytes of body"
    (let [request (http/parse-request
                   (stream "POST /submit HTTP/1.1\r\nContent-Length: 5\r\n\r\nhelloEXTRA"))]
      (is (= "hello" (String. ^bytes (:body request) "UTF-8")))))
  (testing "bare LF line endings are tolerated"
    (is (= "/x" (:path (http/parse-request (stream "GET /x HTTP/1.1\nHost: a\n\n"))))))
  (testing "an empty stream is not a request"
    (is (nil? (http/parse-request (stream "")))))
  (testing "a body that never arrives is an error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ended early"
                          (http/parse-request
                           (stream "POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc"))))))
```

The `EXTRA` after `hello` is there to prove that the body reader stops
where `Content-Length` says and does not just drain the stream.

## What a response looks like

A response is the mirror image: a **status line** with the version, a
three-digit status code and a short phrase, then headers, a blank line,
and the body. We represent one as a map with three keys, and keep the
phrases in a map of their own since nobody can remember them:

```clojure
(def reason-phrases
  "The words that go after the status code on the first line."
  {200 "OK"
   201 "Created"
   204 "No Content"
   301 "Moved Permanently"
   302 "Found"
   304 "Not Modified"
   400 "Bad Request"
   403 "Forbidden"
   404 "Not Found"
   405 "Method Not Allowed"
   500 "Internal Server Error"})
```

A body might be a string, if the handler built some text, or a byte
array, if it read a file, or nothing at all. One function decides what
goes on the wire:

```clojure
(defn body-bytes
  "The bytes of a response body, which may be a string, a byte array or nil."
  ^bytes [body]
  (cond
    (nil? body) (byte-array 0)
    (string? body) (.getBytes ^String body "UTF-8")
    (bytes? body) body
    :else (throw (ex-info "unsupported body" {:body body}))))
```

With that, writing a response is string building. Two headers are
added on the way out. `Content-Length` is computed from the actual
bytes, so that a handler can never get it wrong, and `Connection: close`
tells the client that we will hang up after this response. HTTP/1.1
lets a client send several requests down one connection, and a server
that cares about speed supports that, but it complicates the reading
side considerably and we are after clarity.

```clojure
(defn write-response
  "Write `response` to `out` as an HTTP/1.1 response and flush it.
   Content-Length is computed from the body; Connection: close tells the
   client not to expect a second response on this connection."
  [^OutputStream out {:keys [status headers body] :or {status 200}}]
  (let [data (body-bytes body)
        headers (assoc headers
                       "Content-Length" (str (alength data))
                       "Connection" "close")
        head (str "HTTP/1.1 " status " " (get reason-phrases status "Unknown") "\r\n"
                  (str/join (for [[k v] headers] (str k ": " v "\r\n")))
                  "\r\n")]
    (.write out (.getBytes head "ISO-8859-1"))
    (.write out data)
    (.flush out)))
```

Most responses in this chapter are a status and a line of text, so a
helper makes them:

```clojure
(defn text-response
  "A plain-text response with the given status."
  [status text]
  {:status status
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body (str text "\n")})
```

The first handler is then one line long:

```clojure
(defn hello
  "A handler that greets everyone the same way, whatever they asked for."
  [_request]
  (text-response 200 "Hello, world!"))
```

```clojure
(let [out (java.io.ByteArrayOutputStream.)]
  (write-response out (hello {}))
  (.toString out "UTF-8"))
;; => "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: 14\r\nConnection: close\r\n\r\nHello, world!\n"
```

A **handler** is any function that takes a request map and returns a
response map. `hello` ignores its argument, which is allowed. The
underscore in `_request` is a convention, not syntax: it tells a reader
that the argument is deliberately unused.

## From socket to map and back

Everything between the socket and the handler lives in one function.
It parses a request, calls the handler, writes the response, and deals
with the one kind of exception it knows how to answer:

```clojure
(defn handle-connection
  "Read one request from `sock`, answer it with `(handler request)`, and
   close the connection. A request we cannot parse gets a 400; an
   exception that carries a :status in its data is turned into a
   response with that status."
  [^Socket sock handler]
  (with-open [sock sock]
    (let [in (BufferedInputStream. (.getInputStream sock))
          out (BufferedOutputStream. (.getOutputStream sock))]
      (try
        (when-let [request (parse-request in)]
          (write-response out (handler request)))
        (catch clojure.lang.ExceptionInfo e
          (if-let [status (:status (ex-data e))]
            (write-response out (text-response status (ex-message e)))
            (throw e)))))))
```

The handler is a parameter from the very first stage, and that is the
most important decision in the chapter. `handle-connection` knows how
to read and write HTTP and nothing else; what the server *does* is
entirely the handler's business. Both halves can be tested without the
other, and the next two stages replace the handler without touching
this function.

The `catch` is where the `:status 400` convention pays off. A malformed
request line throws from deep inside `parse-request`, and the client
gets a proper `400 Bad Request` with the reason as its body. Any other
exception is rethrown, which in stage 1 means the server reports it and
closes the connection with no response at all. The browser shows an
unhelpful "connection reset", which is unsatisfying, and stage 3 fixes
it in the right place.

The accept loop is the one from the previous chapter, minus the files:
every connection gets its own future, a timeout so that a client that
connects and says nothing cannot hold a thread forever, and an entry in
an atom so that `:stop` can close whatever is still open. The
explanation of futures, atoms and why shutdown has to happen in that
order is in [the file transfer chapter](../21-ftp/), and is not repeated
here.

```clojure
(defn serve
  "Accept connections on `server` until it is closed, serving each one
   on its own thread with `handler` and dropping it if it is silent for
   `timeout-ms`. `connections` is an atom mapping each open socket to
   the future serving it; `on-error` sees any exception that escapes."
  [^ServerSocket server handler {:keys [connections timeout-ms on-error]}]
  (while (not (.isClosed server))
    (when-let [^Socket sock (try (.accept server)
                                 (catch IOException _ nil))]
      (.setSoTimeout sock timeout-ms)
      (let [fut (future
                  (try
                    (handle-connection sock handler)
                    (catch Exception e (on-error e))
                    (finally
                      (.close sock)
                      (swap! connections dissoc sock))))]
        (swap! connections assoc sock fut)))))

(defn start-server
  "Start serving `handler` on the loopback address. Options: :port
   (default 0, meaning let the operating system choose), :timeout-ms
   (default 10000) and :on-error (default: print to standard error).
   Returns a map with the :port chosen and a :stop function."
  ([handler] (start-server handler {}))
  ([handler {:keys [port timeout-ms on-error]
             :or {port 0 timeout-ms 10000 on-error report-error}}]
   (let [server (ServerSocket. (int port) 50 (InetAddress/getLoopbackAddress))
         connections (atom {})
         worker (future (serve server handler {:connections connections
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

One consequence of futures is worth stating plainly: the handler is
called from many threads at the same time. Nothing in `hello` cares,
because it is a pure function, and that is the general rule. A handler
that only reads its argument and builds a value is safe to run on any
number of threads without a moment's thought, which is a large part of
why this design is so widespread.

To test the server end to end we need a client, and the JDK has one,
`java.net.HttpURLConnection`. The helper namespace `sdx.http.helpers`
wraps it in a function that returns the status, the content type and
the body as a map, with connect and read timeouts set so that a broken
server fails a test instead of hanging it. The first live test is then
short:

```clojure
(deftest hello-over-a-real-socket
  (let [{:keys [port stop]} (http/start-server http/hello)]
    (try
      (let [reply (h/http "GET" port "/anything")]
        (is (= 200 (:status reply)))
        (is (= "text/plain; charset=utf-8" (:content-type reply)))
        (is (= "Hello, world!\n" (:body reply))))
      (testing "several requests in a row each get their own connection"
        (is (= [200 200 200] (map :status (repeatedly 3 #(h/http "GET" port "/"))))))
      (finally
        (stop)))))
```

A real client is too polite to send garbage, so for the error paths
the helpers also have `raw`, which writes whatever bytes it is given to
a socket and returns everything that comes back. It calls
`.shutdownOutput` after writing, which closes only our half of the
connection: the server sees the end of the stream, and we can still
read its reply. Without that, a test that sends nothing at all would
have the server waiting for a request and the test waiting for a
response until one of their timeouts fired.

```clojure
(deftest garbage-gets-a-400
  (let [{:keys [port stop]} (http/start-server http/hello)]
    (try
      (is (str/starts-with? (h/raw port "WHAT IS THIS\r\n\r\n") "HTTP/1.1 400 Bad Request"))
      (is (str/starts-with? (h/raw port "GET / HTTP/1.1\r\nbroken header\r\n\r\n")
                            "HTTP/1.1 400 Bad Request"))
      (testing "a client that connects and leaves is not an error"
        (is (= "" (h/raw port ""))))
      (finally
        (stop)))))
```

## Serving files

Stage 2, in `sdx.http.files`, is a handler that turns a request path
into a file under a directory and sends the file back. Each part of
that sentence hides a decision.

Sending the file back is the easy part, except that the browser needs
to know what kind of thing it is getting. HTTP carries that in the
`Content-Type` header as a **MIME type** such as `text/html` or
`image/png`, and the only clue we have is the file's extension:

```clojure
(def mime-types
  "Content types by file extension. Anything else is sent as plain bytes."
  {"html" "text/html; charset=utf-8"
   "htm"  "text/html; charset=utf-8"
   "css"  "text/css; charset=utf-8"
   "js"   "text/javascript; charset=utf-8"
   "json" "application/json"
   "txt"  "text/plain; charset=utf-8"
   "md"   "text/markdown; charset=utf-8"
   "png"  "image/png"
   "jpg"  "image/jpeg"
   "jpeg" "image/jpeg"
   "gif"  "image/gif"
   "svg"  "image/svg+xml"
   "ico"  "image/x-icon"
   "pdf"  "application/pdf"})

(defn content-type
  "The content type for a file name, chosen by its extension."
  [name]
  (let [ext (second (re-find #"\.([^./]+)$" name))]
    (get mime-types (some-> ext str/lower-case) "application/octet-stream")))
```

```clojure
(map content-type ["index.html" "logo.PNG" "Makefile"])
;; => ("text/html; charset=utf-8" "image/png" "application/octet-stream")
```

`some->` threads a value through the forms that follow it but stops and
returns `nil` as soon as any step produces `nil`, which saves an `if`
around the lower-casing for files that have no extension at all.
`application/octet-stream` is HTTP for "just bytes; save them".

Turning the path into a file is where the care goes. Paths arrive
**percent-encoded**: a space is `%20`, and a character outside ASCII is
the UTF-8 bytes of that character, each written as a percent and two
hex digits. So the first step is to decode them:

```clojure
(defn percent-decode
  "Replace each %XX escape in `s` with the byte it stands for and decode
   the result as UTF-8, so that \"/caf%C3%A9.txt\" becomes \"/café.txt\"."
  [^String s]
  (let [out (ByteArrayOutputStream.)
        n (count s)]
    (loop [i 0]
      (if (>= i n)
        (.toString out "UTF-8")
        (let [c (.charAt s i)]
          (if (and (= c \%) (<= (+ i 3) n))
            (let [code (try (Integer/parseInt (subs s (inc i) (+ i 3)) 16)
                            (catch NumberFormatException _
                              (throw (ex-info (str "bad escape in " (pr-str s))
                                              {:status 400 :path s}))))]
              (.write out (int code))
              (recur (+ i 3)))
            (do (.write out (.getBytes (str c) "UTF-8"))
                (recur (inc i)))))))))
```

```clojure
(percent-decode "/a%20b")           ;; => "/a b"
(percent-decode "/caf%C3%A9.txt")   ;; => "/café.txt"
```

The escapes are decoded to bytes first and the whole thing is decoded
as UTF-8 at the end, rather than each escape being turned into a
character on its own, because `é` is two escapes that only make sense
together. A bad escape throws with `:status 400`, and
`handle-connection` already knows what to do with that.

Now the dangerous part. A decoded path like `/../secret.txt` names a
file *outside* the directory we are serving, and a server that joins the
path to its root and opens the result will happily serve it. The same
holds for `/css/../../secret.txt`, and for `/%2e%2e/secret.txt`, which
decodes to the first one. The defence is to resolve the path and then
check where it ended up:

```clojure
(defn resolve-path
  "Turn a request path into a File under `root`, or nil if the path
   would escape the root. A directory stands for its index.html."
  [^Path root path]
  (let [relative (str/replace (percent-decode path) #"^/+" "")
        target (.normalize (.resolve root relative))]
    (when (.startsWith target root)
      (let [file (.toFile target)]
        (if (.isDirectory file)
          (io/file file "index.html")
          file)))))
```

`java.nio.file.Path` does two things for us here. `.normalize` removes
every `.` and `..` from a path textually, without touching the disk, so
`root/css/../../secret.txt` becomes `secret.txt`'s real location, one
level above the root. `.startsWith` then compares path *components*,
not characters: `/srv/www2` does not start with `/srv/www`, even though
the string does. The leading slash has to be stripped first because
resolving an absolute path against a root ignores the root entirely. If
the result is a directory, we serve the `index.html` inside it, which
is the convention that makes `/` mean the home page.

The handler itself refuses anything but `GET`, then walks through the
three ways a path can fail before reading the file:

```clojure
(defn file-response
  "A 200 response carrying the contents of `file`."
  [^File file]
  {:status 200
   :headers {"Content-Type" (content-type (.getName file))}
   :body (Files/readAllBytes (.toPath file))})

(defn file-handler
  "A handler that serves the files below the directory `root`."
  [root]
  (let [root (-> (io/file root) .toPath .toAbsolutePath .normalize)]
    (fn [{:keys [method path]}]
      (if (not= :get method)
        (hello/text-response 405 "Method Not Allowed")
        (let [file (resolve-path root path)]
          (cond
            (nil? file) (hello/text-response 403 "Forbidden")
            (not (.isFile ^File file)) (hello/text-response 404 "Not Found")
            :else (file-response file)))))))
```

`file-handler` is not a handler; it returns one. The function it
returns remembers `root`, which was normalized once when the handler was
built rather than on every request. That is a closure, and it is the
natural way to give a handler configuration without a global variable
or a map it has to be passed every time.

The distinction between 403 and 404 is deliberate. A file that does not
exist is Not Found. A path that tries to leave the root is Forbidden,
whether or not anything is there, because the server should not reveal
what exists outside the directory it was asked to serve.

Because the handler is a function, most of the tests in
`test/sdx/http/files_test.clj` never open a socket. They build a tiny
site in a temporary directory, make request maps by hand, and look at
the maps that come back:

```clojure
(deftest the-file-handler-is-an-ordinary-function
  (h/with-temp-dir
    (fn [dir]
      (let [handler (files/file-handler (site dir))
            fetch #(handler {:method :get :path % :headers {}})]
        (testing "a file that exists"
          (let [{:keys [status headers body]} (fetch "/notes.txt")]
            (is (= 200 status))
            (is (= "text/plain; charset=utf-8" (headers "Content-Type")))
            (is (= "some notes" (String. ^bytes body "UTF-8")))))
        (testing "the index of a directory"
          (is (= "<h1>Home</h1>" (String. ^bytes (:body (fetch "/")) "UTF-8")))
          (is (= "text/html; charset=utf-8" (get-in (fetch "/") [:headers "Content-Type"]))))
        (testing "a file that does not exist"
          (is (= 404 (:status (fetch "/missing.html"))))
          (is (= 404 (:status (fetch "/css/"))) "a directory with no index"))
        (testing "a path that escapes the root"
          (is (= 403 (:status (fetch "/../secret.txt")))))
        (testing "a method we do not support"
          (is (= 405 (:status (handler {:method :post :path "/notes.txt" :headers {}})))))))))
```

A single live test then checks that the same handler behaves the same
way through `start-server`, including the disguised `/%2e%2e/secret.txt`.

## Handlers and middleware

We now have two handlers and a server that will run either. Stage 3,
`sdx.http.handlers`, is about what happens when we take the handler
contract seriously: if every handler is a function from request to
response, then a function that takes a handler and returns another
handler can add behavior to any of them. That is **middleware**, and it
is the whole design.

Start with the simplest handler of all, and with one that chooses
between others:

```clojure
(defn not-found
  "A handler that answers 404 to everything."
  [_request]
  (hello/text-response 404 "Not Found"))

(defn routes
  "A handler that looks up [method path] in `table`, a map from such
   pairs to handlers, and falls back to `default` for anything else."
  [table default]
  (fn [request]
    (let [handler (get table [(:method request) (:path request)] default)]
      (handler request))))
```

```clojure
(def pick (routes {[:get "/a"] (fn [_] (text-response 200 "a"))} not-found))
(pick {:method :get :path "/zzz"})
;; => {:status 404, :headers {"Content-Type" "text/plain; charset=utf-8"}, :body "Not Found\n"}
```

`routes` is a handler built from handlers. The table is plain data, so
it could be read from a configuration file, printed, or tested by
looking it up, and the fallback is a handler like any other, which is
how a file server gets to be "everything that is not a route".

Middleware looks a little different because it takes a handler and
gives one back. The first fixes the problem we left in stage 1:

```clojure
(defn wrap-errors
  "Middleware: if `handler` throws, answer instead of dropping the
   connection. An exception whose data carries a :status becomes a
   response with that status; anything else is reported to `on-error`
   and becomes a 500."
  ([handler] (wrap-errors handler (fn [_])))
  ([handler on-error]
   (fn [request]
     (try
       (handler request)
       (catch Exception e
         (if-let [status (:status (ex-data e))]
           (hello/text-response status (ex-message e))
           (do (on-error e)
               (hello/text-response 500 "Internal Server Error"))))))))
```

```clojure
((wrap-errors (fn [_] (throw (RuntimeException. "boom")))) {})
;; => {:status 500, :headers {"Content-Type" "text/plain; charset=utf-8"}, :body "Internal Server Error\n"}
((wrap-errors (fn [_] (throw (ex-info "no such thing" {:status 404})))) {})
;; => {:status 404, :headers {"Content-Type" "text/plain; charset=utf-8"}, :body "no such thing\n"}
```

Why is this middleware and not part of `handle-connection`? Because here
it is a pure function from a handler to a handler, and the test for it
is three lines with no sockets; there it would be tangled up with
streams. It also means an application can decide for itself what an
error looks like, perhaps a styled HTML page, by wrapping with something
else. `handle-connection` keeps its own small `catch` only because
parse errors happen before any handler exists to wrap.

Logging is middleware that does something before and after:

```clojure
(defn wrap-logging
  "Middleware: after each request, call `(log entry)` with the method,
   path, status and elapsed milliseconds."
  [handler log]
  (fn [request]
    (let [start (System/nanoTime)
          response (handler request)]
      (log {:method (:method request)
            :path (:path request)
            :status (:status response)
            :ms (/ (- (System/nanoTime) start) 1e6)})
      response)))
```

```clojure
(let [log (atom [])]
  ((wrap-logging hello #(swap! log conj %)) {:method :get :path "/x"})
  @log)
;; => [{:method :get, :path "/x", :status 200, :ms 0.029843}]
```

The log entry is a map and the destination is a function, for the same
reason `on-error` was a parameter in the previous chapter: the test
collects entries in an atom and inspects them, and the command-line
tool prints them. Since handlers run on many threads, the atom matters
here too; `swap!` makes concurrent appends safe where a plain
`println` from several threads would interleave their characters.

And a third, which changes the response on its way out:

```clojure
(defn wrap-headers
  "Middleware: add `extra` headers to every response unless the handler
   already set them."
  [handler extra]
  (fn [request]
    (update (handler request) :headers #(merge extra %))))
```

The three have the same shape, which is the point. Each takes a
handler, returns a function of a request, and in between can look at
the request, change it, call the handler or not, and look at or change
the response. Readers who know other web stacks will recognize this
shape: it is the same one Ring uses in Clojure and a similar one to
WSGI in Python and Rack in Ruby, and it is popular because it composes.

```clojure
(defn app
  "The whole application: a greeting at /hello, files from `root` for
   everything else, every error turned into a response, and every
   request logged through `log`."
  [files log]
  (-> (routes {[:get "/hello"] hello/hello} files)
      (wrap-headers {"Server" "sdx"})
      (wrap-errors)
      (wrap-logging log)))
```

The threading macro `->` puts each result in as the first argument of
the next form, so this reads as a pipeline: start with the routes, add
headers, catch errors, log. But the wrapping goes the other way. The
last wrapper applied is the outermost, so a request passes through
`wrap-logging` first and `routes` last, and the response comes back
through them in reverse. Order matters. With logging outside errors,
the log sees the 500 that `wrap-errors` produced; with errors outside
logging, an exception would fly past the log entry and nothing would be
recorded. A test pins that down:

```clojure
(deftest the-order-of-wrapping-matters
  (let [log (atom [])
        boom (fn [_] (throw (RuntimeException. "boom")))
        logged-outside (-> boom (mw/wrap-errors) (mw/wrap-logging #(swap! log conj %)))
        logged-inside (-> boom (mw/wrap-logging #(swap! log conj %)) (mw/wrap-errors))]
    (is (= 500 (:status (logged-outside request))))
    (is (= [500] (map :status @log)) "logging outside errors sees the 500")
    (reset! log [])
    (is (= 500 (:status (logged-inside request))))
    (is (= [] @log) "logging inside errors never runs")))
```

The last test in `test/sdx/http/handlers_test.clj` runs the whole
`app` behind a real server, fetches `/hello`, the home page and a
missing page, hits a route that throws, and checks that the client sees
a `500` with a body rather than a dropped connection, and that the log
recorded every request except the one the test deliberately broke. It
is the only test in the stage that needs a socket.

## A command-line tool

`sdx.http.main` serves a directory with the full application and prints
one line per request:

```clojure
(defn log-line
  "Print one log entry in the usual one-request-per-line style."
  [{:keys [method path status ms]}]
  (println (format "%-4s %-30s %d %.1fms"
                   (.toUpperCase (name method)) path status ms)))

(defn -main
  "Serve the files under ROOT on PORT (default 8080) until killed."
  [& [root port]]
  (when-not root
    (println "usage: clojure -M -m sdx.http.main ROOT [PORT]")
    (System/exit 1))
  (let [handler (handlers/app (files/file-handler root) log-line)
        server (hello/start-server handler {:port (if port (Long/parseLong port) 8080)})]
    (println (str "serving " root " on http://127.0.0.1:" (:port server) "/"))
    ;; A promise nobody delivers: block until the process is killed.
    @(promise)))
```

```
$ clojure -M -m sdx.http.main www 8080
serving www on http://127.0.0.1:8080/
```

A browser pointed at that address shows `www/index.html`, and `curl`
shows the bytes:

```
$ curl -i http://127.0.0.1:8080/note.txt
HTTP/1.1 200 OK
Server: sdx
Content-Type: text/plain; charset=utf-8
Content-Length: 10
Connection: close

hello cli
```

Asking for `/../etc/passwd` gets a 403, and `/missing` gets a 404, each
in about a hundred lines of Clojure that we can read from top to
bottom.

## Summary

- HTTP is text: a request line, headers, a blank line and an optional
  body, with lines ending in `\r\n`. Reading it means reading bytes,
  because the body may not be text.
- Parse at the boundary into plain maps. A request map and a response
  map are easy to build by hand, easy to inspect and easy to test, and
  they let the networking code and the application code ignore each
  other.
- An exception whose data names a status is an answer, not a bug.
  Turning it into a response at a well-chosen point lets deep code
  report problems without knowing about HTTP.
- Never serve a path without resolving it and checking that it is still
  inside the root. Normalize textually and compare path components, not
  strings, and decode percent escapes before you look.
- A handler is a function from request to response. Middleware is a
  function from handler to handler. Everything else, routing, logging,
  error handling and default headers, is one of those two shapes.
- Wrapping order is evaluation order reversed: the last wrapper applied
  sees the request first and the response last.
- Handlers run on many threads at once. Pure functions make that free;
  shared state has to go in an atom.

## Exercises

1. **HEAD.** A `HEAD` request should get the same headers as a `GET`
   with no body. Write `wrap-head`, which handles `:head` by calling the
   handler with `:get` and dropping the body. What does `write-response`
   then put in `Content-Length`, and is that right?

2. **Query parameters.** Write middleware that parses `:query` into a
   map, so that `?name=Ann&age=7` becomes `{"name" "Ann" "age" "7"}`,
   with percent escapes decoded. Why is middleware a better home for this
   than `parse-request-line`?

3. **Directory listings.** When a directory has no `index.html`, build
   an HTML page listing its files instead of answering 404. Generate the
   links with the percent encoding reversed, and test that a file called
   `a b.txt` is reachable from the listing.

4. **Keep-alive.** Remove `Connection: close` and make
   `handle-connection` loop, reading requests until the client closes.
   What has to be true about `read-body` for this to work, and what does
   the socket timeout now mean?

5. **Streaming files.** `file-response` reads the whole file into
   memory. Allow `:body` to be an `InputStream` and change
   `write-response` to copy it through a buffer, as in
   [the file transfer chapter](../21-ftp/). Where does `Content-Length`
   come from now?

6. **Conditional requests.** Send a `Last-Modified` header with every
   file, and answer `304 Not Modified` with no body when a request's
   `If-Modified-Since` is at least that recent. Is this a change to
   `file-handler` or a new middleware?

7. **Route parameters.** Extend `routes` so that a path like
   `/users/:id` matches `/users/42` and puts `{"id" "42"}` in the
   request. [The glob matcher](../04-glob/) is a good starting point.

8. **Chunked bodies.** A client may send `Transfer-Encoding: chunked`
   instead of `Content-Length`. Read the format up and extend
   `read-body` to handle it, with a test built from a hand-written
   request string.
