(ns sdx.http.hello-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [sdx.http.helpers :as h]
            [sdx.http.hello :as http])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(defn- stream
  "An input stream over the bytes of `s`."
  [^String s]
  (ByteArrayInputStream. (.getBytes s "ISO-8859-1")))

(defn- rendered
  "The text `write-response` produces for `response`."
  [response]
  (let [out (ByteArrayOutputStream.)]
    (http/write-response out response)
    (.toString out "UTF-8")))

(deftest request-lines
  (is (= {:method :get :uri "/" :path "/" :query nil :version "HTTP/1.1"}
         (http/parse-request-line "GET / HTTP/1.1")))
  (is (= {:method :post :uri "/a/b?x=1&y=2" :path "/a/b" :query "x=1&y=2" :version "HTTP/1.0"}
         (http/parse-request-line "POST /a/b?x=1&y=2 HTTP/1.0")))
  (doseq [bad ["" "GET" "GET /" "GET / FTP/1.1" "GET / HTTP/1.1 extra"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed request line"
                          (http/parse-request-line bad))
        bad)))

(deftest headers
  (is (= ["host" "example.com"] (http/parse-header "Host: example.com")))
  (is (= ["x-time" "12:30"] (http/parse-header "X-Time:12:30"))
      "only the first colon splits")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed header"
                        (http/parse-header "no colon here"))))

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

(deftest writing-a-response
  (let [text (rendered {:status 200
                        :headers {"Content-Type" "text/plain"}
                        :body "hi\n"})]
    (is (str/starts-with? text "HTTP/1.1 200 OK\r\n"))
    (is (str/includes? text "Content-Type: text/plain\r\n"))
    (is (str/includes? text "Content-Length: 3\r\n"))
    (is (str/includes? text "Connection: close\r\n"))
    (is (str/ends-with? text "\r\n\r\nhi\n")))
  (testing "a byte-array body and an unknown status"
    (let [text (rendered {:status 418 :body (.getBytes "teapot" "UTF-8")})]
      (is (str/starts-with? text "HTTP/1.1 418 Unknown\r\n"))
      (is (str/ends-with? text "\r\n\r\nteapot"))))
  (testing "a missing body is an empty one"
    (is (str/includes? (rendered {:status 204}) "Content-Length: 0\r\n"))))

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

(deftest an-echo-handler-sees-the-body
  (let [echo (fn [{:keys [method path body]}]
               {:status 200
                :headers {"Content-Type" "text/plain"}
                :body (str (name method) " " path ": " (String. ^bytes body "UTF-8"))})
        {:keys [port stop]} (http/start-server echo)]
    (try
      (is (= "post /in: some text" (:body (h/http "POST" port "/in" "some text"))))
      (finally
        (stop)))))

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

(deftest a-handler-that-throws-drops-the-connection
  (let [failure (promise)
        boom (fn [_] (throw (RuntimeException. "boom")))
        {:keys [port stop]} (http/start-server boom {:on-error #(deliver failure %)})]
    (try
      (is (= "" (h/raw port "GET / HTTP/1.1\r\n\r\n")) "no response at all")
      (is (= "boom" (ex-message (deref failure 5000 nil))))
      (finally
        (stop)))))
