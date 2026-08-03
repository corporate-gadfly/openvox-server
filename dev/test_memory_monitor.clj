(ns test-memory-monitor
  "Backend runner used by monitor_test_memory.sh.
   Executes one test var and prints a parseable metrics line."
  (:require [clojure.string :as str]
            [clojure.test :as test])
  (:import (java.lang.management ManagementFactory)))

(defn heap-used-bytes []
  (-> (ManagementFactory/getMemoryMXBean)
      (.getHeapMemoryUsage)
      (.getUsed)
      long))

(defn parse-args [args]
  (loop [m {} xs (seq args)]
    (if (empty? xs)
      m
      (let [[k v & more] xs]
        (cond
          (= k "--target") (recur (assoc m :target v) more)
          (= k "--namespace") (recur (assoc m :namespace v) more)
          (= k "--test") (recur (assoc m :test v) more)
          :else (recur m more))))))

(defn resolve-target [{:keys [target namespace test]}]
  (cond
    (and target (str/includes? target "/"))
    (let [[ns-sym test-sym] (str/split target #"/" 2)]
      {:namespace (symbol ns-sym)
       :test-var (symbol test-sym)
       :target-str target})

    target
    {:namespace (symbol target)
     :test-var nil
     :target-str target}

    (and namespace test)
    {:namespace (symbol namespace)
     :test-var (symbol test)
     :target-str (str namespace "/" test)}

    :else
    nil))

(defn emit-result! [{:keys [target-str status fail error heap-before heap-after heap-delta message]}]
  (println
   (str "TEST_MEMORY_RESULT"
        "|target=" target-str
        "|status=" status
        "|fail=" fail
        "|error=" error
        "|heap_before=" heap-before
        "|heap_after=" heap-after
        "|heap_delta=" heap-delta
        "|message=" (or message ""))))

(defn test-vars-in-namespace-singlethreaded [ns-sym]
  (->> (ns-interns ns-sym)
       vals
       (filter #(-> % meta :test some?))
       (remove #(-> % meta :multithreaded-only true?))))

(defn -main [& args]
  (let [resolved (resolve-target (parse-args args))]
    (if-not resolved
      (do
        (emit-result! {:target-str "unknown"
                       :status "ERROR"
                       :fail 0
                       :error 1
                       :heap-before 0
                       :heap-after 0
                       :heap-delta 0
                       :message "missing --target ns/test or --namespace ns --test var"})
        (shutdown-agents)
        (System/exit 2))
      (let [{:keys [namespace test-var target-str]} resolved]
        (try
          (require namespace)
          (if test-var
            (let [v (ns-resolve namespace test-var)]
              (if-not v
                (do
                  (emit-result! {:target-str target-str
                                 :status "ERROR"
                                 :fail 0
                                 :error 1
                                 :heap-before 0
                                 :heap-after 0
                                 :heap-delta 0
                                 :message "test var not found"})
                  (shutdown-agents)
                  (System/exit 2))
                (let [before (heap-used-bytes)
                      result (test/test-vars [v])
                      after (heap-used-bytes)
                      fail-count (long (or (:fail result) 0))
                      error-count (long (or (:error result) 0))
                      status (if (and (zero? fail-count) (zero? error-count)) "PASS" "FAIL")]
                  (emit-result! {:target-str target-str
                                 :status status
                                 :fail fail-count
                                 :error error-count
                                 :heap-before before
                                 :heap-after after
                                 :heap-delta (- after before)
                                 :message ""})
                  (shutdown-agents)
                  (System/exit (if (= status "PASS") 0 1)))))
            (let [vars-to-run (test-vars-in-namespace-singlethreaded namespace)
                  before (heap-used-bytes)
                  result (test/test-vars vars-to-run)
                  after (heap-used-bytes)
                  fail-count (long (or (:fail result) 0))
                  error-count (long (or (:error result) 0))
                  status (if (and (zero? fail-count) (zero? error-count)) "PASS" "FAIL")]
              (emit-result! {:target-str target-str
                             :status status
                             :fail fail-count
                             :error error-count
                             :heap-before before
                             :heap-after after
                             :heap-delta (- after before)
                             :message ""})
              (shutdown-agents)
              (System/exit (if (= status "PASS") 0 1))))
          (catch Throwable t
            (emit-result! {:target-str target-str
                           :status "ERROR"
                           :fail 0
                           :error 1
                           :heap-before 0
                           :heap-after 0
                           :heap-delta 0
                           :message (.getMessage t)})
            (shutdown-agents)
            (System/exit 2)))))))

