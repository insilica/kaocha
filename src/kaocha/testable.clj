(ns kaocha.testable
  (:refer-clojure :exclude [load])
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :as t]
            [com.climate.claypoole :as cp]
            [kaocha.classpath :as classpath]
            [kaocha.hierarchy :as hierarchy]
            [kaocha.history :as history]
            [kaocha.output :as output]
            [kaocha.plugin :as plugin]
            [kaocha.result :as result]
            [kaocha.specs :refer [assert-spec]]
            [kaocha.util :as util])
  (:import [java.util.concurrent ArrayBlockingQueue BlockingQueue]))

(def ^:dynamic *fail-fast?*
  "Should testing terminate immediately upon failure or error?"
  nil)

(def ^:dynamic *config* nil)
(def ^:dynamic *test-plan* nil)
(def ^:dynamic *current-testable* nil)

(def ^:dynamic *test-location*
  "Can be bound by a test type to override detecting the current line/file from
  the stacktrace in case of failure. The value should be a map with keys `:file`
  and `:line`."
  nil)


(def REQUIRE_LOCK (Object.))

(defn add-desc [testable description]
  (assoc testable ::desc
         (str (name (::id testable)) " (" description ")")))

(defn- try-require [n]
  (try
    (locking REQUIRE_LOCK 
      (require n))
    true
    (catch java.io.FileNotFoundException e
      false)))

(defn try-load-third-party-lib [type]
  (if (qualified-keyword? type)
    (when-not (try-require (symbol (str (namespace type) "." (name type))))
      (try-require (symbol (namespace type))))
    (try-require (symbol (name type)))))


(defn- retry-assert-spec [type testable n]
  (let [ result (try (assert-spec type testable) (catch Exception _e false))]
    (if (or result (<= n 1)) result
    (retry-assert-spec type testable (dec n))) ;otherwise, retry
    ))

(defn- load-type+validate
  "Try to load a testable type, and validate it both to be a valid generic testable, and a valid instance given the type.

  Implementing a new type means creating a namespace based on type's name, e.g.
  `:my.new/testable` should be in `my.new` or `my.new.testable`.

  This file should implement the multimethods `-load` and `-run`, as well as a
  spec for this type of testable."
  [testable]
  (assert-spec :kaocha/testable testable)
  (let [type (::type testable)]
    (try-load-third-party-lib type)
    (try 
      (assert-spec type testable)
      (catch Exception e
        (output/warn  (format "Could not load %s. This is a known bug in parallelization.\n%s" type e))))))

(defmulti -load
  "Given a testable, load the specified tests, producing a test-plan."
  ::type)

(defmethod -load :default [testable]
  (throw (ex-info (str "No implementation of "
                       `load
                       " for "
                       (pr-str (::type testable)))
                  {:kaocha.error/reason         :kaocha.error/missing-method
                   :kaocha.error/missing-method `load
                   :kaocha/testable             testable})))

(defn ^:no-gen load
  "Given a testable, load the specified tests, producing a test-plan.

  Also performs validation, and lazy loading of the testable type's
  implementation."
  [testable]
  (load-type+validate testable)
  (doseq [path (:kaocha/test-paths testable)]
    (when-not (.exists (io/file path))
      (output/warn "In :test-paths, no such file or directory: " path))
    (when-not (::skip-add-classpath? testable)
      (classpath/add-classpath path)))

  (try
    (binding [*current-testable* testable]
      (let [testable (plugin/run-hook :kaocha.hooks/pre-load-test testable *config*)]
        (if (::skip testable)
          testable
          (binding [*current-testable* testable]
            (let [testable (-load testable)]
              (binding [*current-testable* testable]
                (plugin/run-hook :kaocha.hooks/post-load-test testable *config*)))))))
    (catch Exception t
      (if (hierarchy/suite? testable)
        (assoc testable ::load-error t)
        (throw t)))))

(s/fdef load
  :args (s/cat :testable :kaocha/testable)
  :ret :kaocha.test-plan/testable)

(defmulti -run
  "Given a test-plan, perform the tests, returning the test results."
  (fn [testable test-plan]
    (::type testable)))

(defmethod -run :default [testable test-plan]
  (throw (ex-info (str "No implementation of "
                       `run
                       " for "
                       (pr-str (:kaocha.testable/type testable)))
                  {:kaocha.error/reason         :kaocha.error/missing-method
                   :kaocha.error/missing-method `run
                   :kaocha/testable             testable})))

(defn ^:no-gen run
  "Given a test-plan, perform the tests, returning the test results.

  Also performs validation, and lazy loading of the testable type's
  implementation."
  [testable test-plan]
  (load-type+validate testable)
  (binding [*current-testable* testable]
    (let [run (plugin/run-hook :kaocha.hooks/wrap-run -run test-plan)
          result (run testable test-plan)]
      (if-let [history history/*history*]
        (assoc result
               ::events
               (filter (fn [event]
                         (= (get-in event [:kaocha/testable ::id])
                            (::id testable)))
                       @history))
        result))))

(s/fdef run
        :args (s/cat :testable :kaocha.test-plan/testable
                     :test-plan :kaocha/test-plan)
        :ret :kaocha.result/testable)

(defn load-testables
  "Load a collection of testables, returning a test-plan collection"
  [testables]
  (loop [result []
         [test & testables] testables]
    (if test
      (let [r (if (or (::skip test) (::load-error test))
                test
                (load test))]
        (if (and *fail-fast?* (::load-error r))
          (reduce into [[r] result testables]) ;; move failing test to the front
          (recur (conj result r) testables)))
      result)))

(defn run-testable [test test-plan]
  (let [test (plugin/run-hook :kaocha.hooks/pre-test test test-plan)]
    (cond
      (::load-error test)
      (let [error (::load-error test)
            [file line] (util/compiler-exception-file-and-line error)
            file (::load-error-file test file)
            line (::load-error-line test line)
            thread (.getName (Thread/currentThread))
            m (if-let [message (::load-error-message test)]
                {:type :error
                 :message message
                 :actual error
                 :kaocha/testable test}
                {:type :error
                 :message "Failed loading tests:"
                 :actual error
                 :kaocha/testable test})
            m (cond-> m
                file (assoc :file file)
                line (assoc :line line)
                thread (assoc :thread thread))]
        (t/do-report (assoc m :type :kaocha/begin-suite))
        (binding [*fail-fast?* false]
          (t/do-report m))
        (t/do-report (assoc m :type :kaocha/end-suite))
        (assoc test
               ::events [m]
               :kaocha.result/count 1
               :kaocha.result/error 1))

      (::skip test)
      test

      (or (:kaocha.testable/pending test)
          (-> test ::meta :kaocha/pending))
      (do
        (let [m {:type :kaocha/pending
                 :file (-> test ::meta :file)
                 :line (-> test ::meta :line)
                 :kaocha/testable test}]
          (t/do-report (assoc m :type :kaocha/begin-test))
          (t/do-report m)
          (t/do-report (assoc m :type :kaocha/end-test))
          (assoc test
                 ::events [m]
                 :kaocha.result/count 1
                 :kaocha.result/pending 1)))

      (and (hierarchy/group? test)
           (empty? (remove :kaocha.testable/skip (:kaocha.test-plan/tests test))))
      test

      :else
      (as-> test %
        (run % test-plan)
        (plugin/run-hook :kaocha.hooks/post-test % test-plan)))))


(defn try-run-testable [test test-plan n]
  (let [ result (try (run-testable test test-plan) (catch Exception _e false))]
    (if (or result (> n 1)) result ;success or last try, return
    (try-run-testable test test-plan (dec n))) ;otherwise retry
    ))

(defn f [acc value] 
                     (if (instance? BlockingQueue value)
                       (.drainTo value acc)
                       (.put acc value))
                     acc)

(defn f [acc value] (doto acc (.put value)))

(def q (ArrayBlockingQueue. 1024))
(def r (ArrayBlockingQueue. 1024))

(.put r 5)


(reduce f [q 1 2 r])

(defn run-testables-serial
  "Run a collection of testables, returning a result collection."
  [testables test-plan]
  (doall testables)
  #_(print "run-testables got a collection of size" (count testables)
         " the first of which is "
         (:kaocha.testable/type (first testables))
         )
  (let [load-error? (some ::load-error testables)]
    (loop [result  []
           [test & testables] testables]
      (if test
        (let [test (cond-> test
                     (and load-error? (not (::load-error test)))
                     (assoc ::skip true))
              r (run-testable test test-plan)]
          (if (or (and *fail-fast?* (result/failed? r)) (::skip-remaining? r))
            (reduce into result [[r] testables])
            (recur (conj result r) testables)))
        result))))


(defn run-testables-parallel
  "Run a collection of testables, returning a result collection."
  [testables test-plan]
  (let [num-threads (or (:parallel-threads *config*) (* 2 (inc (cp/ncpus))))
        pred #(= :kaocha.type/ns (:kaocha.testable/type %))
        nses (seq (filter pred testables))
        others (seq (remove pred testables))]
    (concat
     (when others (run-testables-serial others test-plan))
     (when nses
       (cp/with-shutdown! [pool (cp/threadpool num-threads :name "kaocha-test-runner")]
         (doall
          (cp/pmap
            pool
            #(binding [*config*
                       (-> *config*
                           (dissoc :parallel)
                           (update :levels (fn [x] (if (nil? x) 1 (inc x)))))]
               (run-testable % test-plan))
            nses)))))))

(defn run-testables 
  [testables test-plan]
  (if (:parallel *config*)
    (run-testables-parallel testables test-plan)
    (run-testables-serial testables test-plan)))

(defn test-seq [testable]
  (cond->> (mapcat test-seq (remove ::skip (or (:kaocha/tests testable)
                                               (:kaocha.test-plan/tests testable)
                                               (:kaocha.result/tests testable))))
    ;; When calling test-seq on the top level test-plan/result, don't include
    ;; the outer map. When running on an actual testable, do include it.
    (:kaocha.testable/id testable)
    (cons testable)))
