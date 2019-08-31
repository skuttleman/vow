(ns com.ben-allred.vow.core-test
  (:require
    [clojure.core.async :as async]
    [clojure.test :refer [are deftest is testing]]
    [com.ben-allred.vow.core :as vow]))

(deftest resolve-test
  (testing "(resolve)"
    (let [prom (vow/resolve 3)]
      (testing "produces a promise that always resolves to the specified value"
        (is (= [:success 3] @prom))
        (is (= [:success 3] @prom))))))

(deftest reject-test
  (testing "(reject)"
    (let [prom (vow/reject 3)]
      (testing "produces a promise that always rejects to the specified value"
        (is (= [:error 3] @prom))
        (is (= [:error 3] @prom))))))

(deftest ch->prom-test
  (testing "(ch->prom)"
    (let [ch (async/chan)
          prom (vow/ch->prom ch)]
      (testing "when placing a value on the channel"
        (async/put! ch 3)
        (testing "resolves to the specified value"
          (is (= [:success 3] @prom)))

        (testing "and when placing another value on the channel"
          (async/put! ch 31)
          (testing "resolves to the first value"
            (is (= [:success 3] @prom))))))

    (testing "when creating a promise with a success? predicate"
      (testing "and when placing a value on the channel that satisfies the predicate"
        (let [ch (async/chan)
              prom (vow/ch->prom ch odd?)]
          (async/put! ch 3)
          (testing "resolves the value"
            (is (= [:success 3] @prom)))))

      (testing "and when placing a value on the channel that does not satisfy the predicate"
        (let [ch (async/chan)
              prom (vow/ch->prom ch even?)]
          (async/put! ch 3)
          (testing "rejects the value"
            (is (= [:error 3] @prom))))))))

(deftest create-test
  (testing "(create)"
    (testing "when calling resolve with a value"
      (let [prom (vow/create (fn [resolve _] (resolve 3)))]
        (testing "resolves the promise"
          (is (= [:success 3] @prom)))))

    (testing "when calling reject with a value"
      (let [prom (vow/create (fn [_ reject] (reject 3)))]
        (testing "rejects the promise"
          (is (= [:error 3] @prom)))))

    (testing "when calling resolve with a promise that has succeeded"
      (let [prom (vow/create (fn [resolve _] (resolve (vow/resolve (vow/resolve 3)))))]
        (testing "hoists the resolved value"
          (is (= [:success 3] @prom)))))

    (testing "when calling resolve with a promise that has failed"
      (let [prom (vow/create (fn [resolve _] (resolve (vow/reject (vow/reject 3)))))]
        (testing "hoists the rejected value"
          (is (= [:error 3] @prom)))))

    (testing "when calling reject with a promise that has succeeded"
      (let [prom (vow/create (fn [_ reject] (reject (vow/resolve (vow/resolve 3)))))]
        (testing "hoists the resolved value"
          (is (= [:success 3] @prom)))))

    (testing "when calling reject with a promise that has failed"
      (let [prom (vow/create (fn [_ reject] (reject (vow/reject (vow/reject 3)))))]
        (testing "hoists the resolved value"
          (is (= [:error 3] @prom)))))

    (testing "when the promise is never resolved or rejected"
      (let [prom (vow/create (fn [_ _]))]
        (testing "can be deref'ed with a default value"
          (is (= :foo (deref prom 100 :foo))))))

    (testing "when the callback throws an exception"
      (let [exception (ex-info "exception" {:foo :bar})
            prom (vow/create (fn [_ _] (throw exception)))]
        (testing "rejects the promise"
          (is (= [:error exception] @prom)))))))

(deftest then-test
  (testing "(then)"
    (testing "operates on a successful promise"
      (is (= [:success 4] (-> 3 (vow/resolve) (vow/then inc) (deref)))))

    (testing "does not operate on a failed promise"
      (is (= [:success 3] (-> 3 (vow/resolve) (vow/catch dec) (deref)))))

    (testing "does not alter original promise"
      (let [prom (vow/resolve 3)]
        (-> prom (vow/then inc) (deref))
        (is (= [:success 3] @prom))))

    (testing "is chainable"
      (is (= [:success 11] (-> 3
                               (vow/resolve)
                               (vow/then inc dec)
                               (vow/then (partial * 2))
                               (vow/catch (partial / 17))
                               (vow/then (partial + 3))
                               (deref)))))

    (testing "can be used to handle failures"
      (is (= [:success 2] (-> 3
                              (vow/reject)
                              (vow/then #(throw (ex-info "bad!" {:x %})) dec)
                              (deref)))))

    (testing "when the callback throws an exception"
      (let [exception (ex-info "bad" {})]
        (testing "rejects the promise"
          (is (= [:error exception] (-> 3
                                        (vow/resolve)
                                        (vow/then (fn [_] (throw exception)))
                                        (deref)))))))))

(deftest catch-test
  (testing "(catch)"
    (testing "does not operate on a successful promise"
      (is (= [:error 3] (-> 3 (vow/reject) (vow/then inc) (deref)))))

    (testing "operates on a failed promise"
      (is (= [:error 2] (-> 3 (vow/reject) (vow/catch (comp vow/reject dec)) (deref)))))

    (testing "does not alter original promise"
      (let [prom (vow/reject 3)]
        (-> prom (vow/catch inc) (deref))
        (is (= [:error 3] @prom))))

    (testing "is chainable"
      (is (= [:error 8] (-> 3
                            (vow/reject)
                            (vow/then inc (comp vow/reject dec))
                            (vow/then (partial * 2))
                            (vow/catch (comp vow/reject (partial * 4)))
                            (vow/then (partial + 3))
                            (deref)))))

    (testing "when handling a failed promise"
      (let [prom (-> 3 (vow/reject) (vow/then inc dec))]
        (testing "resolves the promise"
          (is (= [:success 2] @prom)))))

    (testing "when the callback throws an exception"
      (let [exception (ex-info "bad" {})]
        (testing "rejects the promise"
          (is (= [:error exception] (-> 3
                                        (vow/reject)
                                        (vow/catch (fn [_] (throw exception)))
                                        (deref)))))))))

(deftest peek-test
  (testing "(peek)"
    (testing "when peeking on the value of a promise"
      (let [peeks (atom [])
            result (-> 3
                       (vow/reject)
                       (vow/peek (partial swap! peeks conj))
                       (vow/catch dec)
                       (vow/peek (partial swap! peeks conj))
                       (vow/then dec)
                       (deref))]
        (testing "the callback is called with the promise values"
          (is (= [[:error 3] [:success 2]]
                 @peeks)))

        (testing "the promise is not effected by the return value of the callback"
          (is (= [:success 1] result)))))

    (testing "when providing two callbacks to peek"
      (let [successes (atom [])
            failures (atom [])
            peek-success (partial swap! successes conj)
            peek-failure (partial swap! failures conj)
            result (-> 3
                       (vow/resolve)
                       (vow/peek peek-success peek-failure)
                       (vow/then (comp vow/reject inc))
                       (vow/peek peek-success peek-failure)
                       (vow/then (partial + -7) (partial + 7))
                       (deref))]
        (testing "the success callback is called with only successful values"
          (is (= [3] @successes)))

        (testing "the failure callback is called with only failed values"
          (is (= [4] @failures)))

        (testing "the promise is not effected by the return value of the callback"
          (is (= [:success 11] result)))))

    (testing "when the callback throws an exception"
      (testing "does not effect the promise"
        (is (= [:success 4] (-> 3
                                (vow/resolve)
                                (vow/peek #(throw (ex-info "bad" {:x %})))
                                (vow/then inc)
                                (deref))))))))
