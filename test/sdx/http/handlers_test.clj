(ns sdx.http.handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [sdx.http.files :as files]
            [sdx.http.handlers :as mw]
            [sdx.http.helpers :as h]
            [sdx.http.hello :as http]))

(def request {:method :get :path "/x" :headers {}})

(deftest routing-by-method-and-path
  (let [handler (mw/routes {[:get "/a"] (fn [_] (http/text-response 200 "a"))
                            [:post "/a"] (fn [_] (http/text-response 201 "made a"))}
                           mw/not-found)]
    (is (= 200 (:status (handler {:method :get :path "/a"}))))
    (is (= 201 (:status (handler {:method :post :path "/a"}))))
    (is (= 404 (:status (handler {:method :get :path "/b"}))))
    (is (= 404 (:status (handler {:method :delete :path "/a"}))))))

(deftest errors-become-responses
  (let [seen (atom [])
        boom (mw/wrap-errors (fn [_] (throw (RuntimeException. "boom")))
                             #(swap! seen conj (ex-message %)))
        lost (mw/wrap-errors (fn [_] (throw (ex-info "no such thing" {:status 404}))))
        fine (mw/wrap-errors http/hello)]
    (testing "an unexpected exception is a 500 and is reported"
      (is (= 500 (:status (boom request))))
      (is (= ["boom"] @seen)))
    (testing "an exception that names a status is that status"
      (is (= 404 (:status (lost request))))
      (is (= "no such thing\n" (:body (lost request)))))
    (testing "a handler that does not throw is untouched"
      (is (= (http/hello request) (fine request))))))

(deftest logging-sees-every-request
  (let [log (atom [])
        handler (mw/wrap-logging http/hello #(swap! log conj %))]
    (handler request)
    (handler (assoc request :path "/y"))
    (is (= [[:get "/x" 200] [:get "/y" 200]]
           (map (juxt :method :path :status) @log)))
    (is (every? number? (map :ms @log)))))

(deftest adding-headers
  (let [handler (mw/wrap-headers http/hello {"Server" "sdx" "Content-Type" "ignored"})
        headers (:headers (handler request))]
    (is (= "sdx" (headers "Server")))
    (is (= "text/plain; charset=utf-8" (headers "Content-Type"))
        "the handler's own header wins")))

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

(deftest the-whole-application
  (h/with-temp-dir
    (fn [dir]
      (let [root (io/file dir "www")
            _ (h/write-file! (io/file root "index.html") "<h1>Home</h1>")
            log (atom [])
            boom (fn [_] (throw (RuntimeException. "boom")))
            app (-> (mw/routes {[:get "/boom"] boom} (mw/app (files/file-handler root) #(swap! log conj %)))
                    (mw/wrap-errors))
            {:keys [port stop]} (http/start-server app)]
        (try
          (is (= "Hello, world!\n" (:body (h/http "GET" port "/hello"))))
          (is (= "sdx" (:server (h/http "GET" port "/hello"))))
          (is (= "<h1>Home</h1>" (:body (h/http "GET" port "/"))))
          (is (= 404 (:status (h/http "GET" port "/nothing"))))
          (testing "a crash is a 500 with a body, not a dropped connection"
            (is (= {:status 500 :body "Internal Server Error\n"}
                   (select-keys (h/http "GET" port "/boom") [:status :body]))))
          (is (= [[:get "/hello" 200] [:get "/hello" 200] [:get "/" 200] [:get "/nothing" 404]]
                 (map (juxt :method :path :status) @log)))
          (finally
            (stop)))))))
