(ns sdx.http.files-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [sdx.http.files :as files]
            [sdx.http.helpers :as h]
            [sdx.http.hello :as http]))

(deftest content-types
  (is (= "text/html; charset=utf-8" (files/content-type "index.html")))
  (is (= "image/png" (files/content-type "logo.PNG")))
  (is (= "application/octet-stream" (files/content-type "archive.tar.xyz")))
  (is (= "application/octet-stream" (files/content-type "Makefile"))))

(deftest percent-decoding
  (is (= "/plain" (files/percent-decode "/plain")))
  (is (= "/a b" (files/percent-decode "/a%20b")))
  (is (= "/café.txt" (files/percent-decode "/caf%C3%A9.txt")))
  (is (= "100%" (files/percent-decode "100%")) "a stray % is left alone")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bad escape"
                        (files/percent-decode "/%zz"))))

(defn- site
  "A small web site in `dir` with a page, a stylesheet and a secret
   next door that must never be served."
  [dir]
  (let [root (io/file dir "www")]
    (h/write-file! (io/file root "index.html") "<h1>Home</h1>")
    (h/write-file! (io/file root "css" "site.css") "body { color: red }")
    (h/write-file! (io/file root "notes.txt") "some notes")
    (h/write-file! (io/file dir "secret.txt") "do not serve")
    root))

(deftest resolving-paths-stays-inside-the-root
  (h/with-temp-dir
    (fn [dir]
      (let [root (-> (site dir) .toPath .toAbsolutePath .normalize)
            resolve #(some->> (files/resolve-path root %) .toPath (.relativize root) str)]
        (is (= "index.html" (resolve "/")) "the root directory means its index")
        (is (= "notes.txt" (resolve "/notes.txt")))
        (is (= "css/site.css" (resolve "/css/site.css")))
        (is (= "notes.txt" (resolve "/css/../notes.txt")) "going up inside the root is fine")
        (is (= "notes.txt" (resolve "//notes.txt")) "extra slashes do not matter")
        (is (nil? (resolve "/../secret.txt")) "going above the root is not")
        (is (nil? (resolve "/css/../../secret.txt")))
        (is (nil? (resolve "/%2e%2e/secret.txt")) "nor is going up in disguise")))))

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

(deftest serving-files-over-a-real-socket
  (h/with-temp-dir
    (fn [dir]
      (let [{:keys [port stop]} (http/start-server (files/file-handler (site dir)))]
        (try
          (let [reply (h/http "GET" port "/css/site.css")]
            (is (= 200 (:status reply)))
            (is (= "text/css; charset=utf-8" (:content-type reply)))
            (is (= "body { color: red }" (:body reply))))
          (is (= 200 (:status (h/http "GET" port "/"))))
          (is (= 404 (:status (h/http "GET" port "/nope.html"))))
          (is (= 403 (:status (h/http "GET" port "/%2e%2e/secret.txt"))))
          (finally
            (stop)))))))
