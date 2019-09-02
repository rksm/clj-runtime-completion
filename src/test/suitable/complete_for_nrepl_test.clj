(ns suitable.complete-for-nrepl-test
  (:require [clojure.test :as t :refer [deftest is run-tests use-fixtures testing compose-fixtures]]
            [cider.piggieback :as piggieback]
            [cider.nrepl :refer [cider-nrepl-handler cider-middleware]]
            [nrepl.core :as nrepl]
            [nrepl.server :refer [start-server default-handler]]
            [suitable.middleware :refer [wrap-complete-standalone]]))

(def ^:dynamic *handler* cider-nrepl-handler)
(def ^:dynamic *session* nil)

(def ^:dynamic *server* nil)
(def ^:dynamic *transport* nil)

(defn message
  ([msg] (message msg true))
  ([msg combine-responses?]
   (let [responses (nrepl/message *session* msg)]
     (if combine-responses?
       (nrepl/combine-responses responses)
       responses))))

(defmacro start [renv-form]
  `(do
     (def handler (default-handler #'piggieback/wrap-cljs-repl #'wrap-complete-standalone))
     (def server    (start-server :handler handler))
     (def transport (nrepl/connect :port (:port server)))
     (def client  (nrepl/client transport 3000))
     (def session (nrepl/client-session client))
     (alter-var-root #'*server*    (constantly server))
     (alter-var-root #'*transport* (constantly transport))
     (alter-var-root #'*session* (constantly session))
     (dorun (message
             {:op :eval
              :code (nrepl/code (require '[cider.piggieback :as piggieback])
                                (piggieback/cljs-repl ~renv-form))}))
     (dorun (message {:op :eval
                      :code (nrepl/code (require 'clojure.data))}))))

(defn stop []
  (message {:op :eval :code (nrepl/code :cljs/quit)})
  (.close *transport*)
  (.close *server*))

(comment

  (start (cljs.repl.nashorn/repl-env :debug false))
  (start (cljs.repl.node/repl-env))
  (stop)
  )

(defmacro with-repl-env [renv & body]
  `(try
     (start ~renv)
     ~@body
     (finally
       (stop))))

;; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(deftest sanity-test-nashorn
  (require 'cljs.repl.nashorn)
  (with-repl-env (cljs.repl.nashorn/repl-env :debug false)
    (testing "cljs repl is active"
      (let [response (message {:op :eval
                               :code (nrepl/code (js/Object.))})]
        (is (= "cljs.user" (:ns response)))
        (is (= ["#js {}"] (:value response)))
        (is (= #{"done"} (:status response)))))))

(deftest sanity-test-node
  (require 'cljs.repl.node)
  (with-repl-env (cljs.repl.node/repl-env)
    (testing "cljs repl is active"
      (let [response (message {:op :eval
                               :code (nrepl/code (js/Object.))})]
        (is (= "cljs.user" (:ns response)))
        (is (= ["#js {}"] (:value response)))
        (is (= #{"done"} (:status response)))))))

(deftest suitable-nashorn
  (with-repl-env (cljs.repl.nashorn/repl-env :debug false)
    (testing "js global completion"
      (let [response (message {:op "complete"
                               :ns "cljs.user"
                               :symbol "js/Ob"})
            candidates (:completions response)]
        (is (= [{:candidate "js/Object", :ns "js", :type "function"}] candidates))))

    (testing "manages context state"
        (message {:op "complete"
                  :ns "cljs.user"
                  :symbol ".xxxx"
                  :context "(__prefix__ js/Object)"})
        (let [response (message {:op "complete"
                                 :ns "cljs.user"
                                 :symbol ".key"
                                 :context ":same"})
              candidates (:completions response)]
          (is (= [{:ns "js/Object", :candidate ".keys" :type "function"}] candidates))))))

(deftest suitable-node
  (require 'cljs.repl.node)
  (with-repl-env (cljs.repl.node/repl-env)
    (testing "js global completion"
      (let [response (message {:op "complete"
                               :ns "cljs.user"
                               :symbol "js/Ob"})
            candidates (:completions response)]
        (is (= [{:candidate "js/Object", :ns "js", :type "function"}] candidates))))))


(comment
  (run-tests))
