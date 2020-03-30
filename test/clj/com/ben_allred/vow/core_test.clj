(ns com.ben-allred.vow.core-test
  (:require
    [clojure.core.async :as async]
    [clojure.test :refer [are deftest is testing]]
    [com.ben-allred.vow.core :as v])
  (:import
    (clojure.lang Keyword)
    (java.util Date)))

(deftest resolve-test
  (testing "(resolve)"
    (let [prom (v/resolve 3)]
      (testing "produces a promise that always resolves to the specified value"
        (is (= [:success 3] @prom))
        (is (= [:success 3] @prom))))))

(deftest reject-test
  (testing "(reject)"
    (let [prom (v/reject 3)]
      (testing "produces a promise that always rejects to the specified value"
        (is (= [:error 3] @prom))
        (is (= [:error 3] @prom))))))

(deftest ch->prom-test
  (testing "(ch->prom)"
    (let [ch (async/chan)
          prom (v/ch->prom ch)]
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
              prom (v/ch->prom ch odd?)]
          (async/put! ch 3)
          (testing "resolves the value"
            (is (= [:success 3] @prom)))))

      (testing "and when placing a value on the channel that does not satisfy the predicate"
        (let [ch (async/chan)
              prom (v/ch->prom ch even?)]
          (async/put! ch 3)
          (testing "rejects the value"
            (is (= [:error 3] @prom))))))))

(deftest create-test
  (testing "(create)"
    (testing "when calling resolve with a value"
      (let [prom (v/create (fn [resolve _] (resolve 3)))]
        (testing "resolves the promise"
          (is (= [:success 3] @prom)))))

    (testing "when calling reject with a value"
      (let [prom (v/create (fn [_ reject] (reject 3)))]
        (testing "rejects the promise"
          (is (= [:error 3] @prom)))))

    (testing "when calling resolve with a promise that has succeeded"
      (let [prom (v/create (fn [resolve _] (resolve (v/resolve (v/resolve 3)))))]
        (testing "hoists the resolved value"
          (is (= [:success 3] @prom)))))

    (testing "when calling resolve with a promise that has failed"
      (let [prom (v/create (fn [resolve _] (resolve (v/reject (v/reject 3)))))]
        (testing "hoists the rejected value"
          (is (= [:error 3] @prom)))))

    (testing "when calling reject with a promise that has succeeded"
      (let [prom (v/create (fn [_ reject] (reject (v/resolve (v/resolve 3)))))]
        (testing "hoists the resolved value"
          (is (= [:success 3] @prom)))))

    (testing "when calling reject with a promise that has failed"
      (let [prom (v/create (fn [_ reject] (reject (v/reject (v/reject 3)))))]
        (testing "hoists the resolved value"
          (is (= [:error 3] @prom)))))

    (testing "when the promise is never resolved or rejected"
      (let [prom (v/create (fn [_ _]))]
        (testing "can be deref'ed with a default value"
          (is (= :foo (deref prom 20 :foo))))))

    (testing "when the callback throws an exception"
      (let [exception (ex-info "exception" {:foo :bar})
            prom (v/create (fn [_ _] (throw exception)))]
        (testing "rejects the promise"
          (is (= [:error exception] @prom)))))))

(deftest then-test
  (testing "(then)"
    (testing "operates on a successful promise"
      (is (= [:success 4] (-> 3 (v/resolve) (v/then inc) (deref)))))

    (testing "does not operate on a failed promise"
      (is (= [:success 3] (-> 3 (v/resolve) (v/catch dec) (deref)))))

    (testing "does not alter original promise"
      (let [prom (v/resolve 3)]
        (-> prom (v/then inc) (deref))
        (is (= [:success 3] @prom))))

    (testing "is chainable"
      (is (= [:success 11] (-> 3
                               (v/resolve)
                               (v/then inc dec)
                               (v/then (partial * 2))
                               (v/catch (partial / 17))
                               (v/then (partial + 3))
                               (deref)))))

    (testing "can be used to handle failures"
      (is (= [:success 2] (-> 3
                              (v/reject)
                              (v/then #(throw (ex-info "bad!" {:x %})) dec)
                              (deref)))))

    (testing "when the callback throws an exception"
      (let [exception (ex-info "bad" {})]
        (testing "rejects the promise"
          (is (= [:error exception] (-> 3
                                        (v/resolve)
                                        (v/then (fn [_] (throw exception)))
                                        (deref)))))))))

(deftest catch-test
  (testing "(catch)"
    (testing "does not operate on a successful promise"
      (is (= [:error 3] (-> 3 (v/reject) (v/then inc) (deref)))))

    (testing "operates on a failed promise"
      (is (= [:error 2] (-> 3 (v/reject) (v/catch (comp v/reject dec)) (deref)))))

    (testing "does not alter original promise"
      (let [prom (v/reject 3)]
        (-> prom (v/catch inc) (deref))
        (is (= [:error 3] @prom))))

    (testing "is chainable"
      (is (= [:error 8] (-> 3
                            (v/reject)
                            (v/then inc (comp v/reject dec))
                            (v/then (partial * 2))
                            (v/catch (comp v/reject (partial * 4)))
                            (v/then (partial + 3))
                            (deref)))))

    (testing "when handling a failed promise"
      (let [prom (-> 3 (v/reject) (v/then inc dec))]
        (testing "resolves the promise"
          (is (= [:success 2] @prom)))))

    (testing "when the callback throws an exception"
      (let [exception (ex-info "bad" {})]
        (testing "rejects the promise"
          (is (= [:error exception] (-> 3
                                        (v/reject)
                                        (v/catch (fn [_] (throw exception)))
                                        (deref)))))))))

(deftest peek-test
  (testing "(peek)"
    (testing "when peeking on the value of a promise"
      (let [peeks (atom [])
            result (-> 3
                       (v/reject)
                       (v/peek (partial swap! peeks conj))
                       (v/catch dec)
                       (v/peek (partial swap! peeks conj))
                       (v/then dec)
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
                       (v/resolve)
                       (v/peek peek-success peek-failure)
                       (v/then (comp v/reject inc))
                       (v/peek peek-success peek-failure)
                       (v/then (partial + -7) (partial + 7))
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
                                (v/resolve)
                                (v/peek #(throw (ex-info "bad" {:x %})))
                                (v/then inc)
                                (deref))))
        (is (= [:success 4] (-> 3
                                v/resolve
                                (v/peek nil nil)
                                (v/then inc)
                                deref)))))))

(deftest all-test
  (testing "(all)"
    (testing "collects success values"
      (is (= [:success [:a :b :c]]
             (-> [(v/resolve :a)
                  (v/ch->prom (async/go :b))
                  (v/create (fn [resolve _] (resolve :c)))]
                 (v/all)
                 (deref)))))

    (testing "works on maps"
      (is (= [:success {:a 1 :b 2 :c 3}]
             (-> {:a (v/resolve 1)
                  :b (v/ch->prom (async/go 2))
                  :c (v/create (fn [resolve _] (resolve 3)))}
                 (v/all)
                 (deref)))))

    (testing "rejects with a single error"
      (is (= [:error :boom!]
             (-> [(v/resolve 1) (v/reject :boom!) (v/resolve 3)]
                 (v/all)
                 (deref)))))

    (testing "fails fast"
      (let [before (.getTime (Date.))
            _ (-> [(v/sleep :foo 20) (v/reject :boom!)]
                  v/all
                  deref)
            after (.getTime (Date.))]
        (is (< (- after before) 20))))))

(deftest any-test
  (testing "(any)"
    (testing "resolves with a single success"
      (is (= [:success :b]
             (-> [(v/reject :a) (v/resolve :b) (v/reject :c)]
                 v/any
                 deref))))

    (testing "collects error values"
      (is (= [:error [:a :b :c]]
             (-> [(v/reject :a)
                  (v/sleep (v/reject :b) 10)
                  (v/create (fn [_ reject] (reject :c)))]
                 v/any
                 deref))))

    (testing "works on maps"
      (is (= [:error {:a 1 :b 2 :c 3}]
             (-> {:a (v/reject 1)
                  :b (v/sleep (v/reject 2) 10)
                  :c (v/create (fn [_ reject] (reject 3)))}
                 v/any
                 deref))))

    (testing "succeeds fast"
      (let [before (.getTime (Date.))
            _ (-> [(v/sleep :foo 20) (v/resolve :bar)]
                  v/any
                  deref)
            after (.getTime (Date.))]
        (is (< (- after before) 20))))))

(deftest then->test
  (testing "(then->)"
    (testing "threads the success path"
      (is (= [:error 15]
             (-> (v/resolve 3)
                 (v/then-> inc (inc) (* 3) v/reject inc)
                 (deref)))))

    (testing "has no effect on failed promises"
      (is (= [:error 3]
             (-> (v/reject 3)
                 (v/then-> inc (inc) (* 3) v/reject inc)
                 (deref)))))))

(deftest vow-test
  (testing "(vow)"
    (testing "when the body yields a value"
      (is (= [:success 3]
             @(v/vow (+ 1 2)))))

    (testing "when the body throws an exception"
      (let [ex (ex-info "an exception" {:boom? true})]
        (is (= [:error ex]
               @(v/vow (throw ex))))))

    (testing "when the body yields a promise"
      (is (= [:success :foo]
             @(v/vow (v/resolve :foo))))
      (is (= [:error :bar]
             @(v/vow (v/reject :bar)))))))

(deftest deref!-test
  (testing "(deref!)"
    (testing "when the promise resolves"
      (let [prom (v/resolve 12)]
        (testing "returns the resolved value"
          (is (= 12 (v/deref! prom))))))

    (testing "when the promise rejects with a value"
      (let [prom (v/reject 13)]
        (testing "throws an exception"
          (try (v/deref! prom)
               (is false "test should not get here")
               (catch Throwable ex
                 (is (= 13 (:error (ex-data ex)))))))))

    (testing "when the promise rejects with an exception"
      (let [ex (ex-info "an exception" {:foo :bar})
            prom (v/create (fn [_ _] (throw ex)))]
        (testing "throws the exception"
          (try (v/deref! prom)
               (is false "test should not get here")
               (catch Throwable ex'
                 (is (= ex ex')))))))

    (testing "when deref'ing with a timeout"
      (let [v (v/deref! (v/create (fn [_ _])) 10 ::default)]
        (testing "resolves to the default value"
          (is (= ::default v)))))))

(deftest always-test
  (testing "(always)"
    (testing "when the promise is pending"
      (let [x (atom 0)]
        (v/always (v/ch->prom (async/promise-chan))
          (swap! x inc)
          13)
        (testing "does not execute the body"
          (is (zero? @x)))))

    (testing "when the promise resolves"
      (let [x (atom 0)
            result (v/always (v/resolve :something)
                     (swap! x inc)
                     (v/reject 17)
                     13)]
        (testing "returns the last expression"
          (is (= [:success 13] @result)))

        (testing "evaluates all expressions"
          (is (= 1 @x)))))

    (testing "when the promise rejects"
      (let [x (atom 0)
            result (v/always (v/reject :something)
                     (swap! x inc)
                     (v/reject 17)
                     13)]
        (testing "returns the last expression"
          (is (= [:success 13] @result)))

        (testing "evaluates all expressions"
          (is (= 1 @x)))))))

(deftest and-test
  (testing "(and)"
    (testing "when all promises resolve"
      (let [result (v/and (v/resolve)
                          (+ 1 2)
                          (v/resolve 17)
                          (v/resolve :hooray!))]
        (testing "returns the last result"
          (is (= [:success :hooray!] @result)))))

    (testing "when one promise rejects"
      (let [result (v/and (v/resolve)
                          (+ 1 2)
                          (v/reject 17)
                          (v/resolve :hooray!))]
        (testing "returns the rejection"
          (is (= [:error 17] @result)))))

    (testing "when the initial promise rejects"
      (let [result (v/and (v/reject :bad)
                          (+ 1 2)
                          17
                          :hooray!)]
        (testing "returns the rejection"
          (is (= [:error :bad] @result)))))))

(deftest or-test
  (testing "(or)"
    (testing "when all promises reject"
      (let [expected (ex-info "bad" {:foo :bar})
            result (v/or (v/reject :foo)
                         (v/reject)
                         (throw expected))]
        (testing "returns the last rejection"
          (is (= [:error expected] @result)))))

    (testing "when one promise resolves"
      (let [result (v/or (v/reject)
                         (v/reject 17)
                         (+ 1 2)
                         (v/reject :boo!))]
        (testing "returns the rejection"
          (is (= [:success 3] @result)))))

    (testing "when the initial promise resolves"
      (let [result (v/or (v/resolve :good)
                         (v/reject 17)
                         (throw (ex-info "bad" {}))
                         (v/reject :boo!))]
        (testing "returns the resolved value"
          (is (= [:success :good] @result)))))))

(deftest sleep-test
  (testing "(sleep)"
    (let [now (.getTime (Date.))
          result @(v/sleep :result 20)
          after (.getTime (Date.))]

      (testing "waits the configured amount"
        (is (>= (- after now) 20)))

      (testing "resolves the value"
        (is (= [:success :result] result))))))

(deftest first-test
  (testing "(first)"
    (testing "resolves to the first result"
      (are [promises expected] (= expected @(v/first promises))
        [(v/sleep :foo 20) (v/sleep :bar 40) (v/sleep :baz 60)] [:success :foo]
        [(v/sleep :foo 60) (v/sleep :bar 40) (v/sleep :baz 20)] [:success :baz]
        [(v/sleep :foo 20) (v/sleep :bar 40) (v/reject :baz)] [:error :baz]
        [(v/sleep :foo 60) (v/sleep :bar 40) (v/sleep (v/reject :baz) 20)] [:error :baz]
        [(v/sleep :foo 40) (v/sleep :bar 20) (v/sleep (v/reject :baz) 60)] [:success :bar]
        [(v/sleep (v/reject :foo) 20) (v/sleep :bar 40) (v/sleep :baz 60)] [:error :foo]))))

(deftest await-test
  (testing "(await)"
    (testing "executes expectedly"
      (let [ex (ex-info "an exception" {:some :info})]
        (are [expected promise] (= expected @promise)
          [:success nil] (v/await [])
          [:success [1 2 3]] (v/await [a 1
                                       b 2
                                       c 3]
                               [a b c])
          [:success [:x :y :z]] (v/await [x (v/resolve :x)
                                          y (v/resolve :y)
                                          z (v/resolve :z)]
                                  [x y z])
          [:error :middle] (v/await []
                             (v/resolve :beginning)
                             (v/reject :middle)
                             (v/resolve :end))
          [:error ex] (v/await [_ (v/resolve)]
                        (throw ex))
          [:error ex] (v/await [x (v/resolve :x)
                                y (throw ex)]
                        [x y])
          [:error :reject] (v/await [x (v/reject :reject)]
                             (throw ex)
                             x))))

    (testing "evaluates forms lexically"
      (let [before (.getTime (Date.))
            result @(v/await [x (v/resolve 7)
                              [y z] (v/all [(v/resolve (inc x))
                                            (v/sleep :z 20)])]
                      (+ 1 2)
                      (v/sleep 20)
                      (v/await [a (v/sleep [z y] 20)]
                        {:a a}))
            after (.getTime (Date.))]
        (is (= [:success {:a [:z 8]}] result))
        (is (>= (- after before) 60))))))

(deftest attempt-test
  (testing "(attempt)"
    (testing "executes expectedly"
      (are [expected promise] (= expected @promise)
        [:success 3] (v/attempt (+ 1 2)
                                (catch _
                                  17))
        [:success 17] (v/attempt (+ 1 2)
                                 (+ 4 5)
                                 17)
        [:error :ex] (v/attempt (+ 1 2)
                                (v/reject :ex)
                                (+ 7 8))
        [:success :any] (v/attempt (+ 1 2)
                                   (throw (ex-info "" {}))
                                   (catch any
                                     :any))
        [:success [:number 17]] (v/attempt (v/reject 17)
                                           (catch ^String s
                                             [:string s])
                                           (catch ^Number n
                                             [:number n])
                                           (catch ^Keyword k
                                             [:keyword k]))
        [:success [:any 3]] (v/attempt (v/reject 3)
                                       (catch any
                                         [:any any])
                                       (catch ^Number n
                                         [:number n]))
        [:error :keyword] (v/attempt (v/reject :keyword)
                                     (catch ^Number n
                                       [:number n])
                                     (catch ^String s
                                       [:string s]))))

    (testing "when there is a finally clause"
      (let [side-effect (atom 4)
            before (.getTime (Date.))
            result @(v/attempt (v/sleep 20)
                               (* 2 3)
                               (finally (v/sleep 20)
                                      (swap! side-effect inc)))
            after (.getTime (Date.))]
        (testing "handles finally"
          (is (= 5 @side-effect))
          (is (= [:success 6] result))
          (is (>= (- after before) 40))))

      (testing "and when the finally clause errors"
        (let [side-effect (atom 4)
              before (.getTime (Date.))
              result @(v/attempt (v/sleep 20)
                                 (* 2 3)
                                 (finally (v/sleep 20)
                                        (v/reject :final-bomb!)
                                        (swap! side-effect inc)))
              after (.getTime (Date.))]
          (testing "rejects the promise"
            (is (= 4 @side-effect))
            (is (= [:error :final-bomb!] result))
            (is (>= (- after before) 40)))))

      (testing "and when the promise is caught"
        (let [side-effect (atom 4)
              before (.getTime (Date.))
              result @(v/attempt (v/reject "oh no!")
                                 (catch ^Number n
                                   [:number n])
                                 (catch ^String _
                                   :recovered)
                                 (finally (v/sleep 20)
                                        (swap! side-effect inc)))
              after (.getTime (Date.))]
          (testing "handles finally"
            (is (= 5 @side-effect))
            (is (= [:success :recovered] result))
            (is (>= (- after before) 20))))))))
