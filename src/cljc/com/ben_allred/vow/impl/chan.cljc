(ns com.ben-allred.vow.impl.chan
  (:refer-clojure :exclude [resolve])
  (:require
    [clojure.core.async :as async]
    [clojure.core.async.impl.protocols :as async.protocols]
    [com.ben-allred.vow.impl.protocol :as proto])
  #?(:clj
     (:import
       (clojure.lang IBlockingDeref IDeref IRecord))))

#?(:clj
   (prefer-method print-method IDeref IRecord))

#?(:cljs
   (declare ->ChanPromise))

(defrecord ^:private PromiseResult [status value]
  proto/IPromiseResult
  (result [_]
    [(keyword (name status)) value]))

(defn ^:private try* [cb]
  (try (cb)
       (catch #?(:clj Throwable :default :default) ex
         (->PromiseResult ::error ex))))

(defn ^:private resolve* [val]
  (if (instance? PromiseResult val)
    val
    (->PromiseResult ::success val)))

(defn ^:private reject* [val]
  (if (instance? PromiseResult val)
    val
    (->PromiseResult ::error val)))

(defn ^:private promise? [x]
  (satisfies? proto/IPromise x))

(defn ^:private ->ch
  ([x]
   (->ch (async/promise-chan) x))
  ([ch x]
   (async/go-loop [val x]
     (cond
       (and (not (promise? val)) (satisfies? async.protocols/ReadPort val))
       (recur (async/<! val))

       (promise? val)
       (let [ch' (async/chan)]
         (proto/then val
                     (comp (partial async/put! ch') resolve*)
                     (comp (partial async/put! ch') reject*))
         (recur (async/<! ch')))
       :else (async/put! ch (resolve* val))))
   ch))

(defn ^:private handle! [cb]
  (->ch (try* cb)))

(defrecord ChanPromise [ch]
  proto/IPromise
  (then [_ on-success on-error]
    (let [next-ch (async/promise-chan)]
      (async/go
        (async/>! next-ch (let [{:keys [status value]} (async/<! (->ch ch))]
                            (-> (if (= status ::success) on-success on-error)
                                (partial value)
                                (handle!)
                                (async/<!)))))
      (->ChanPromise next-ch)))

  #?@(:clj [IDeref
            (deref [_]
              (proto/result (async/<!! ch)))

            IBlockingDeref
            (deref [_ ms default]
              (let [val (-> [ch (async/timeout ms)]
                            (async/alts!!)
                            (first))]
                (if val
                  (proto/result val)
                  default)))]))

(defn ^:private on* [cb]
  (fn [val]
    (if (promise? val)
      val
      (->ChanPromise (->ch (cb val))))))

(defn create [cb]
  (let [ch (async/promise-chan)
        on (partial ->ch ch)
        on-success (comp on (on* resolve*))
        on-error (comp on (on* reject*))]
    (try (cb on-success on-error)
         (catch #?(:clj Throwable :default :default) ex
           (on-error ex)))
    (->ChanPromise ch)))
