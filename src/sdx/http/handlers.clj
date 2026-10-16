(ns sdx.http.handlers
  "Stage 3 of the web server: a handler is a function from a request map
   to a response map, and middleware is a function that takes a handler
   and returns a new handler. Everything the server does beyond reading
   and writing messages is built from those two shapes."
  (:require [sdx.http.hello :as hello]))

;; ---------------------------------------------------------------------
;; Handlers.

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

;; ---------------------------------------------------------------------
;; Middleware.

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

(defn wrap-headers
  "Middleware: add `extra` headers to every response unless the handler
   already set them."
  [handler extra]
  (fn [request]
    (update (handler request) :headers #(merge extra %))))

;; ---------------------------------------------------------------------
;; Putting them together.

(defn app
  "The whole application: a greeting at /hello, files from `root` for
   everything else, every error turned into a response, and every
   request logged through `log`."
  [files log]
  (-> (routes {[:get "/hello"] hello/hello} files)
      (wrap-headers {"Server" "sdx"})
      (wrap-errors)
      (wrap-logging log)))
