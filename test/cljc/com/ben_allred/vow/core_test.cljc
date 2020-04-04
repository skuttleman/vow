(ns com.ben-allred.vow.core-test
  (:require
    #?(:clj [com.ben-allred.vow.test.async :refer [async]])
    [clojure.core.async :as async]
    [clojure.test :refer [are #?(:cljs async) deftest is testing]]
    [com.ben-allred.vow.core :as v #?@(:cljs [:include-macros true])])
  #?(:clj
     (:import
       (clojure.lang Keyword)
       (java.util Date))))

(defn ^:private prom->ch [prom]
  (let [ch (async/promise-chan)]
    (v/then prom
            #(async/put! ch [:success %])
            #(async/put! ch [:error %]))
    ch))

(defn now []
  #?(:clj  (Date.)
     :cljs (js/Date.)))

(deftest resolve-test
  (testing "(resolve)"
    (async done
      (async/go
        (let [prom (v/resolve 3)]
          (testing "produces a promise that always resolves to the specified value"
            (is (= [:success 3] (async/<! (prom->ch prom))))
            (is (= [:success 3] (async/<! (prom->ch prom))))))

        (done)))))

(deftest reject-test
  (testing "(reject)"
    (async done
      (async/go
        (let [prom (v/reject 3)]
          (testing "produces a promise that always rejects to the specified value"
            (is (= [:error 3] (async/<! (prom->ch prom))))
            (is (= [:error 3] (async/<! (prom->ch prom))))))

        (done)))))

(deftest ch->prom-test
  (testing "(ch->prom)"
    (async done
      (async/go
        (let [ch (async/chan)
              prom (v/ch->prom ch)]
          (testing "when placing a value on the channel"
            (async/>! ch 3)
            (testing "resolves to the specified value"
              (is (= [:success 3] (async/<! (prom->ch prom)))))

            (testing "and when placing another value on the channel"
              (async/put! ch 31)
              (testing "resolves to the first value"
                (is (= [:success 3] (async/<! (prom->ch prom))))))))

        (testing "when creating a promise with a success? predicate"
          (testing "and when placing a value on the channel that satisfies the predicate"
            (let [ch (async/chan)
                  prom (v/ch->prom ch odd?)]
              (async/>! ch 3)
              (testing "resolves the value"
                (is (= [:success 3] (async/<! (prom->ch prom)))))))

          (testing "and when placing a value on the channel that does not satisfy the predicate"
            (let [ch (async/chan)
                  prom (v/ch->prom ch even?)]
              (async/>! ch 3)
              (testing "rejects the value"
                (is (= [:error 3] (async/<! (prom->ch prom))))))))

        (done)))))

(deftest create-test
  (testing "(create)"
    (async done
      (async/go
        (testing "when calling resolve with a value"
          (let [prom (v/create (fn [resolve _] (resolve 3)))]
            (testing "resolves the promise"
              (is (= [:success 3] (async/<! (prom->ch prom)))))))

        (testing "when calling reject with a value"
          (let [prom (v/create (fn [_ reject] (reject 3)))]
            (testing "rejects the promise"
              (is (= [:error 3] (async/<! (prom->ch prom)))))))

        (testing "when calling resolve with a promise that has succeeded"
          (let [prom (v/create (fn [resolve _] (resolve (v/resolve (v/resolve 3)))))]
            (testing "hoists the resolved value"
              (is (= [:success 3] (async/<! (prom->ch prom)))))))

        (testing "when calling resolve with a promise that has failed"
          (let [prom (v/create (fn [resolve _] (resolve (v/reject (v/reject 3)))))]
            (testing "hoists the rejected value"
              (is (= [:error 3] (async/<! (prom->ch prom)))))))

        (testing "when calling reject with a promise that has succeeded"
          (let [prom (v/create (fn [_ reject] (reject (v/resolve (v/resolve 3)))))]
            (testing "hoists the resolved value"
              (is (= [:success 3] (async/<! (prom->ch prom)))))))

        (testing "when calling reject with a promise that has failed"
          (let [prom (v/create (fn [_ reject] (reject (v/reject (v/reject 3)))))]
            (testing "hoists the resolved value"
              (is (= [:error 3] (async/<! (prom->ch prom)))))))

        #?(:clj
           (testing "when the promise is never resolved or rejected"
             (let [prom (v/create (fn [_ _]))]
               (testing "can be deref'ed with a default value"
                 (is (= :foo (deref prom 20 :foo)))))))

        (testing "when the callback throws an exception"
          (let [exception (ex-info "exception" {:foo :bar})
                prom (v/create (fn [_ _] (throw exception)))]
            (testing "rejects the promise"
              (is (= [:error exception] (async/<! (prom->ch prom)))))))

        (done)))))

(deftest then-test
  (testing "(then)"
    (async done
      (async/go
        (testing "operates on a successful promise"
          (is (= [:success 4] (async/<! (-> 3 v/resolve (v/then inc) prom->ch)))))

        (testing "does not operate on a failed promise"
          (is (= [:success 3] (async/<! (-> 3 v/resolve (v/catch dec) prom->ch)))))

        (testing "does not alter original promise"
          (let [prom (v/resolve 3)]
            (async/<! (-> prom (v/then inc) prom->ch))
            (is (= [:success 3] (async/<! (prom->ch prom))))))

        (testing "is chainable"
          (is (= [:success 11] (async/<! (-> 3
                                             v/resolve
                                             (v/then inc dec)
                                             (v/then (partial * 2))
                                             (v/catch (partial / 17))
                                             (v/then (partial + 3))
                                             prom->ch)))))

        (testing "can be used to handle failures"
          (is (= [:success 2] (async/<! (-> 3
                                            v/reject
                                            (v/then #(throw (ex-info "bad!" {:x %})) dec)
                                            prom->ch)))))

        (testing "when the callback throws an exception"
          (let [exception (ex-info "bad" {})]
            (testing "rejects the promise"
              (is (= [:error exception] (async/<! (-> 3
                                                      v/resolve
                                                      (v/then (fn [_] (throw exception)))
                                                      prom->ch)))))))

        (done)))))

(deftest catch-test
  (testing "(catch)"
    (async done
      (async/go
        (testing "does not operate on a successful promise"
          (is (= [:error 3] (async/<! (-> 3 v/reject (v/then inc) prom->ch)))))

        (testing "operates on a failed promise"
          (is (= [:error 2] (async/<! (-> 3 v/reject (v/catch (comp v/reject dec)) prom->ch)))))

        (testing "does not alter original promise"
          (let [prom (v/reject 3)]
            (async/<! (-> prom (v/catch inc) prom->ch))
            (is (= [:error 3] (async/<! (prom->ch prom))))))

        (testing "is chainable"
          (is (= [:error 8] (async/<! (-> 3
                                          v/reject
                                          (v/then inc (comp v/reject dec))
                                          (v/then (partial * 2))
                                          (v/catch (comp v/reject (partial * 4)))
                                          (v/then (partial + 3))
                                          prom->ch)))))

        (testing "when handling a failed promise"
          (let [prom (-> 3 v/reject (v/then inc dec))]
            (testing "resolves the promise"
              (is (= [:success 2] (async/<! (prom->ch prom)))))))

        (testing "when the callback throws an exception"
          (let [exception (ex-info "bad" {})]
            (testing "rejects the promise"
              (is (= [:error exception] (async/<! (-> 3
                                                      v/reject
                                                      (v/catch (fn [_] (throw exception)))
                                                      prom->ch)))))))

        (done)))))

(deftest peek-test
  (testing "(peek)"
    (async done
      (async/go
        (testing "when peeking on the value of a promise"
          (let [peeks (atom [])
                result (async/<! (-> 3
                                     v/reject
                                     (v/peek (partial swap! peeks conj))
                                     (v/catch dec)
                                     (v/peek (partial swap! peeks conj))
                                     (v/then dec)
                                     prom->ch))]
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
                result (async/<! (-> 3
                                     v/resolve
                                     (v/peek peek-success peek-failure)
                                     (v/then (comp v/reject inc))
                                     (v/peek peek-success peek-failure)
                                     (v/then (partial + -7) (partial + 7))
                                     prom->ch))]
            (testing "the success callback is called with only successful values"
              (is (= [3] @successes)))

            (testing "the failure callback is called with only failed values"
              (is (= [4] @failures)))

            (testing "the promise is not effected by the return value of the callback"
              (is (= [:success 11] result)))))

        (testing "when the callback throws an exception"
          (testing "does not effect the promise"
            (is (= [:success 4] (async/<! (-> 3
                                              v/resolve
                                              (v/peek #(throw (ex-info "bad" {:x %})))
                                              (v/then inc)
                                              prom->ch))))
            (is (= [:success 4] (async/<! (-> 3
                                              v/resolve
                                              (v/peek nil nil)
                                              (v/then inc)
                                              prom->ch))))))

        (done)))))

(deftest all-test
  (testing "(all)"
    (async done
      (async/go
        (testing "collects success values"
          (is (= [:success [:a :b :c]]
                 (async/<! (-> [(v/resolve :a)
                                (v/ch->prom (async/go :b))
                                (v/create (fn [resolve _] (resolve :c)))]
                               v/all
                               prom->ch)))))

        (testing "works on maps"
          (is (= [:success {:a 1 :b 2 :c 3}]
                 (async/<! (-> {:a (v/resolve 1)
                                :b (v/ch->prom (async/go 2))
                                :c (v/create (fn [resolve _] (resolve 3)))}
                               v/all
                               prom->ch)))))

        (testing "rejects with a single error"
          (is (= [:error :boom!]
                 (async/<! (-> [(v/resolve 1) (v/reject :boom!) (v/resolve 3)]
                               v/all
                               prom->ch)))))

        (testing "fails fast"
          (let [before (.getTime (now))
                _ (async/<! (-> [(v/sleep :foo 20) (v/reject :boom!)]
                                v/all
                                prom->ch))
                after (.getTime (now))]
            (is (< (- after before) 20))))

        (done)))))

(deftest any-test
  (testing "(any)"
    (async done
      (async/go
        (testing "resolves with a single success"
          (is (= [:success :b]
                 (async/<! (-> [(v/reject :a) (v/resolve :b) (v/reject :c)]
                               v/any
                               prom->ch)))))

        (testing "collects error values"
          (is (= [:error [:a :b :c]]
                 (async/<! (-> [(v/reject :a)
                                (v/sleep (v/reject :b) 10)
                                (v/create (fn [_ reject] (reject :c)))]
                               v/any
                               prom->ch)))))

        (testing "works on maps"
          (is (= [:error {:a 1 :b 2 :c 3}]
                 (async/<! (-> {:a (v/reject 1)
                                :b (v/sleep (v/reject 2) 10)
                                :c (v/create (fn [_ reject] (reject 3)))}
                               v/any
                               prom->ch)))))

        (testing "succeeds fast"
          (let [before (.getTime (now))
                _ (async/<! (-> [(v/sleep :foo 20) (v/resolve :bar)]
                                v/any
                                prom->ch))
                after (.getTime (now))]
            (is (< (- after before) 20))))

        (done)))))

(deftest then->test
  (testing "(then->)"
    (async done
      (async/go
        (testing "threads the success path"
          (is (= [:error 15]
                 (async/<! (-> (v/resolve 3)
                               (v/then-> inc (inc) (* 3) v/reject inc)
                               prom->ch)))))

        (testing "has no effect on failed promises"
          (is (= [:error 3]
                 (async/<! (-> (v/reject 3)
                               (v/then-> inc (inc) (* 3) v/reject inc)
                               prom->ch)))))

        (testing "threads to promise creating expressions"
          (let [before (.getTime (now))
                _ (async/<! (-> (v/resolve 3)
                                (v/then-> inc (inc) (* 3) (v/sleep 20) inc)
                                prom->ch))
                after (.getTime (now))]
            (is (>= (- after before) 20))))

        (done)))))

(deftest vow-test
  (testing "(vow)"
    (async done
      (async/go
        (testing "when the body yields a value"
          (is (= [:success 3]
                 (async/<! (prom->ch (v/vow (+ 1 2)))))))

        (testing "when the body throws an exception"
          (let [ex (ex-info "an exception" {:boom? true})]
            (is (= [:error ex]
                   (async/<! (prom->ch (v/vow (throw ex))))))))

        (testing "when the body yields a promise"
          (is (= [:success :foo]
                 (async/<! (prom->ch (v/vow (v/resolve :foo))))))
          (is (= [:error :bar]
                 (async/<! (prom->ch (v/vow (v/reject :bar)))))))

        (done)))))

(deftest always-test
  (testing "(always)"
    (async done
      (async/go
        (testing "when the promise is pending"
          (let [x (atom 0)]
            (async/alts! [(async/timeout 20)
                          (prom->ch (v/always (v/ch->prom (async/promise-chan))
                                      (swap! x inc)
                                      13))])
            (testing "does not execute the body"
              (is (zero? @x)))))

        (testing "when the promise resolves"
          (let [x (atom 0)
                result (prom->ch (v/always (v/resolve :something)
                                   (swap! x inc)
                                   (v/resolve 17)
                                   13))]
            (testing "returns the last expression"
              (is (= [:success 13] (async/<! result))))

            (testing "evaluates all expressions"
              (is (= 1 @x)))))

        (testing "when the promise rejects"
          (let [x (atom 0)
                result (prom->ch (v/always (v/reject :something)
                                   (swap! x inc)
                                   (v/reject 17)
                                   13))]
            (testing "returns the last expression"
              (is (= [:success 13] (async/<! result))))

            (testing "evaluates all expressions"
              (is (= 1 @x)))))

        (done)))))

(deftest and-test
  (testing "(and)"
    (async done
      (async/go
        (testing "when all promises resolve"
          (let [result (v/and (v/resolve)
                              (+ 1 2)
                              (v/resolve 17)
                              (v/resolve :hooray!))]
            (testing "returns the last result"
              (is (= [:success :hooray!] (async/<! (prom->ch result)))))))

        (testing "when one promise rejects"
          (let [evaluated? (atom false)
                result (v/and (v/resolve)
                              (+ 1 2)
                              (v/reject 17)
                              (reset! evaluated? true)
                              (v/resolve :hooray!))]
            (testing "returns the rejection"
              (is (= [:error 17] (async/<! (prom->ch result)))))

            (testing "short circuits evaluation"
              (is (not @evaluated?)))))

        (testing "when the initial promise rejects"
          (let [result (v/and (v/reject :bad)
                              (+ 1 2)
                              17
                              :hooray!)]
            (testing "returns the rejection"
              (is (= [:error :bad] (async/<! (prom->ch result)))))))

        (done)))))

(deftest or-test
  (testing "(or)"
    (async done
      (async/go
        (testing "when all promises reject"
          (let [expected (ex-info "bad" {:foo :bar})
                result (v/or (v/reject :foo)
                             (v/reject)
                             (throw expected))]
            (testing "returns the last rejection"
              (is (= [:error expected] (async/<! (prom->ch result)))))))

        (testing "when one promise resolves"
          (let [result (v/or (v/reject)
                             (v/reject 17)
                             (+ 1 2)
                             (v/reject :boo!))]
            (testing "returns the rejection"
              (is (= [:success 3] (async/<! (prom->ch result)))))))

        (testing "when the initial promise resolves"
          (let [result (v/or (v/resolve :good)
                             (v/reject 17)
                             (throw (ex-info "bad" {}))
                             (v/reject :boo!))]
            (testing "returns the resolved value"
              (is (= [:success :good] (async/<! (prom->ch result)))))))

        (done)))))

(deftest sleep-test
  (testing "(sleep)"
    (async done
      (async/go
        (let [before (.getTime (now))
              result (async/<! (prom->ch (v/sleep :result 20)))
              after (.getTime (now))]

          (testing "waits the configured amount"
            (is (>= (- after before) 20)))

          (testing "resolves the value"
            (is (= [:success :result] result))))

        (done)))))

(deftest first-test
  (testing "(first)"
    (async done
      (async/go
        (testing "resolves to the first result"
          (are [promises expected] (= expected (async/<! (prom->ch (v/first promises))))
            [(v/sleep :foo 20) (v/sleep :bar 40) (v/sleep :baz 60)] [:success :foo]
            [(v/sleep :foo 60) (v/sleep :bar 40) (v/sleep :baz 20)] [:success :baz]
            [(v/sleep :foo 20) (v/sleep :bar 40) (v/reject :baz)] [:error :baz]
            [(v/sleep :foo 60) (v/sleep :bar 40) (v/sleep (v/reject :baz) 20)] [:error :baz]
            [(v/sleep :foo 40) (v/sleep :bar 20) (v/sleep (v/reject :baz) 60)] [:success :bar]
            [(v/sleep (v/reject :foo) 20) (v/sleep :bar 40) (v/sleep :baz 60)] [:error :foo]))

        (done)))))

(deftest await-test
  (testing "(await)"
    (async done
      (async/go
        (testing "executes expectedly"
          (let [ex (ex-info "an exception" {:some :info})]
            (are [expected promise] (= expected (async/<! (prom->ch promise)))
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
          (let [before (.getTime (now))
                result (async/<! (prom->ch (v/await [x (v/resolve 7)
                                                     [y z] (v/all [(v/resolve (inc x))
                                                                   (v/sleep :z 20)])]
                                             (+ 1 2)
                                             (v/sleep 20)
                                             (v/await [a (v/sleep [z y] 20)]
                                               {:a a}))))
                after (.getTime (now))]
            (is (= [:success {:a [:z 8]}] result))
            (is (>= (- after before) 60))))

        (done)))))

(deftest attempt-test
  (testing "(attempt)"
    (async done
      (async/go
        (testing "executes expectedly"
          (are [expected promise] (= expected (async/<! (prom->ch promise)))
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
                                               (catch #?(:clj ^String s :cljs ^js/String s)
                                                 [:string s])
                                               (catch #?(:clj ^Number n :cljs ^js/Number n)
                                                 [:number n])
                                               (catch ^Keyword k
                                                 [:keyword k]))
            [:success [:any 3]] (v/attempt (v/reject 3)
                                           (catch any
                                             [:any any])
                                           (catch #?(:clj ^Number n :cljs ^js/Number n)
                                             [:number n]))
            [:error :keyword] (v/attempt (v/reject :keyword)
                                         (catch #?(:clj ^Number n :cljs ^js/Number n)
                                           [:number n])
                                         (catch #?(:clj ^String s :cljs ^js/String s)
                                           [:string s]))))

        (testing "when there is a finally clause"
          (let [side-effect (atom 4)
                before (.getTime (now))
                result (async/<! (prom->ch (v/attempt (v/sleep 20)
                                                      (* 2 3)
                                                      (finally (v/sleep 20)
                                                               (swap! side-effect inc)))))
                after (.getTime (now))]
            (testing "handles finally"
              (is (= 5 @side-effect))
              (is (= [:success 6] result))
              (is (>= (- after before) 40))))

          (testing "and when the finally clause errors"
            (let [side-effect (atom 4)
                  before (.getTime (now))
                  result (async/<! (prom->ch (v/attempt (v/sleep 20)
                                                        (* 2 3)
                                                        (finally (v/sleep 20)
                                                                 (v/reject :final-bomb!)
                                                                 (swap! side-effect inc)))))
                  after (.getTime (now))]
              (testing "rejects the promise"
                (is (= 4 @side-effect))
                (is (= [:error :final-bomb!] result))
                (is (>= (- after before) 40)))))

          (testing "and when the promise is caught"
            (let [side-effect (atom 4)
                  before (.getTime (now))
                  result (async/<! (prom->ch (v/attempt (v/reject "oh no!")
                                                        (catch #?(:clj ^Number n :cljs ^js/Number n)
                                                          [:number n])
                                                        (catch #?(:clj ^String _ :cljs ^js/String _)
                                                          :recovered)
                                                        (finally (v/sleep 20)
                                                                 (swap! side-effect inc)))))
                  after (.getTime (now))]
              (testing "handles finally"
                (is (= 5 @side-effect))
                (is (= [:success :recovered] result))
                (is (>= (- after before) 20))))))

        (done)))))

#?(:clj
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
             (is (= ::default v))))))))
