(ns visorcraft.mongreldb.durable-retrieve-test
  (:require [clojure.test :refer [deftest is testing]]
            [visorcraft.mongreldb.core :as m]))

(def fixture
  {:query_id "abcdefabcdefabcdefabcdefabcdefab"
   :status "committed"
   :state "completed"
   :server_state "completed"
   :terminal_state "committed"
   :committed true
   :committed_statements 1
   :last_commit_epoch 17
   :last_commit_hlc {:physical_micros 1700000000000000
                     :logical 3
                     :node_tiebreaker 7}
   :outcome {:committed true
             :last_commit_epoch 17
             :last_commit_hlc {:physical_micros 1700000000000000
                               :logical 3
                               :node_tiebreaker 7}
             :serialization "succeeded"
             :serialization_state "succeeded"
             :terminal_state "committed"}
   :durable {:committed true
             :last_commit_epoch 17
             :last_commit_hlc {:physical_micros 1700000000000000
                               :logical 3
                               :node_tiebreaker 7}
             :serialization "succeeded"
             :serialization_state "succeeded"
             :terminal_state "committed"}})

(deftest parse-query-status-structural-hlc
  (let [status (m/parse-query-status fixture)
        hlc (m/commit-hlc status)]
    (is (true? (:committed status)))
    (is (some? hlc))
    (is (= 1700000000000000 (:physical_micros hlc)))
    (is (= 3 (:logical hlc)))
    (is (= 7 (:node_tiebreaker hlc)))
    (is (= "succeeded" (m/serialization-state status)))
    (is (= 17 (get-in status [:outcome :last_commit_epoch])))))

(deftest parse-commit-hlc-absent
  (is (nil? (m/parse-commit-hlc nil)))
  (is (nil? (m/parse-commit-hlc {})))
  (is (nil? (m/parse-commit-hlc {:logical 1}))))
