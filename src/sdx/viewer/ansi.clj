(ns sdx.viewer.ansi
  "A Screen that talks to a real terminal using ANSI escape codes.

   This is Unix-only: the terminal's size and its raw mode come from
   the `stty` command, which is run against /dev/tty through a
   ProcessBuilder, and the program must be started from a terminal.
   None of that can be exercised by an automated test, so this
   namespace is kept as thin as possible. The parts that do not need a
   terminal (building escape sequences, decoding the bytes a terminal
   sends for a key, and what the Terminal record writes) are tested
   with in-memory streams.

   Run the viewer with:  clojure -M -m sdx.viewer.main some-file.txt"
  (:require [clojure.string :as str]
            [sdx.viewer.screen :as scr]))

;; ---------------------------------------------------------------------
;; Escape sequences: what we send to the terminal.

(def ^:private esc
  "The Control Sequence Introducer that starts every command we send."
  "\u001b[")

(defn goto
  "The sequence that moves the terminal's cursor to `row`, `col`
   (0-based here; the terminal counts from 1)."
  [row col]
  (str esc (inc row) ";" (inc col) "H"))

(def clear-screen "Blank the whole screen." (str esc "2J"))
(def alt-screen-on "Switch to the alternate screen, saving the shell's." (str esc "?1049h"))
(def alt-screen-off "Switch back to the shell's screen." (str esc "?1049l"))

;; ---------------------------------------------------------------------
;; Decoding what the terminal sends us for a key press.

(def ^:private csi-letters
  "Final letters of the escape sequences for the keys we care about."
  {\A :up, \B :down, \C :right, \D :left, \H :home, \F :end})

(def ^:private csi-numbers
  "Numeric codes in sequences of the form ESC [ n ~."
  {1 :home, 3 :delete, 4 :end, 5 :page-up, 6 :page-down, 7 :home, 8 :end})

(defn- csi
  "Read the rest of an ESC [ sequence from `next-byte` and name the key."
  [next-byte]
  (loop [params []]
    (let [b (next-byte)]
      (cond
        (neg? b) :unknown
        (<= 0x30 b 0x3f) (recur (conj params (char b)))
        :else (let [final (char b)
                    n (parse-long (apply str params))]
                (or (csi-letters final)
                    (when (= final \~) (csi-numbers n))
                    :unknown))))))

(defn decode-key
  "Turn the byte `b` just read from the terminal into a key, pulling any
   further bytes of an escape sequence from `next-byte`, a function that
   returns the next pending byte or -1 if there is none."
  [b next-byte]
  (cond
    (neg? b) nil
    (= b 27) (let [b2 (next-byte)]
               (cond
                 (= b2 (int \[)) (csi next-byte)
                 (= b2 (int \O)) (get csi-letters (char (max 0 (next-byte))) :unknown)
                 :else :escape))
    (or (= b 13) (= b 10)) :enter
    (= b 9) :tab
    (or (= b 127) (= b 8)) :backspace
    (< b 32) (keyword (str "ctrl-" (char (+ b 96))))
    :else (str (char b))))

;; ---------------------------------------------------------------------
;; The screen itself.

(defrecord Terminal [^java.io.Writer out ^java.io.InputStream in height width]
  scr/Screen
  (dimensions [_] [height width])
  (erase [_]
    (.write out ^String clear-screen))
  (write-at [_ row col text]
    (when (and (<= 0 row) (< row height) (<= 0 col) (< col width))
      (.write out ^String (str (goto row col) (scr/clip text (- width col))))))
  (move-cursor [_ row col]
    (.write out ^String (goto row col))
    (.flush out))
  (read-key [_]
    (decode-key (.read in)
                #(if (pos? (.available in)) (.read in) -1))))

;; ---------------------------------------------------------------------
;; Talking to stty. Unix-only, and only from a real terminal.

(defn stty
  "Run `stty` with `args` against the controlling terminal and return
   whatever it prints, trimmed."
  [args]
  (let [proc (.start (ProcessBuilder. ["sh" "-c" (str "stty " args " < /dev/tty")]))
        out (slurp (.getInputStream proc))]
    (.waitFor proc)
    (str/trim out)))

(defn terminal-size
  "The controlling terminal's [height width], as reported by stty."
  []
  (let [[rows cols] (str/split (stty "size") #"\s+")]
    [(parse-long rows) (parse-long cols)]))

(defn with-terminal
  "Put the terminal into raw mode on the alternate screen, call `f`
   with a Terminal screen, and put everything back however `f` ends."
  [f]
  (let [saved (stty "-g")
        [height width] (terminal-size)
        out *out*
        screen (->Terminal out System/in height width)]
    (stty "raw -echo")
    (.write out ^String alt-screen-on)
    (.flush out)
    (try
      (f screen)
      (finally
        (.write out ^String alt-screen-off)
        (.flush out)
        (stty saved)))))
