(ns com.ben-allred.vow.core
  "A library to wrap core.async channels with chainable left/right (or success/failure) handling."
  (:refer-clojure :exclude [next peek resolve])
  (:require
    [com.ben-allred.vow.impl.chan :as impl.chan]
    [com.ben-allred.vow.impl.protocol :as proto])
  #?(:clj
     (:import
       (clojure.lang IBlockingDeref IDeref IRecord))))

(defn ^:private try* [cb val]
  (try (when (ifn? cb)
         (cb val))
       (catch #?(:clj Throwable :cljs :default) _))
  val)

(defn resolve
  "Creates a promise that resolves with `val`."
  [val]
  (impl.chan/resolve val))

(defn reject
  "Creates a promise that rejects with `err`."
  [err]
  (impl.chan/reject err))

(defn ch->prom
  "Given a core.async channel and an optional success? predicate, creates a promise with the first value pulled off
  the channel. The promise will resolve or reject according to the result of (success? (async/<! ch)). Defaults
  to always resolving."
  ([ch]
   (ch->prom ch (constantly true)))
  ([ch success?]
   (impl.chan/ch->prom ch success?)))

(defn create
  "Creates a promise that resolves or rejects at the discretion of cb.
  `cb` should be a two arg function accepting `resolve` and `reject` that will
  \"resolve\" or \"reject\" the promise respectively.

  (create (fn [resolve reject]
            (let [default (gensym)
                  result (deref (future (do-something)) 1000 default)]
              (if (= default result)
                (reject (ex-info \"Took too long\" {}))
                (resolve result)))))"
  [cb]
  (impl.chan/create cb))

(defn then
  "Handles the success path (or success and failure path) of a promise. If the handler fn returns
  an IPromise, it will be hoisted. If the handler fn throws, it will produce a rejected promise."
  ([promise on-success]
   (then promise on-success reject))
  ([promise on-success on-error]
   (proto/then promise on-success on-error)))

(defn catch
  "Handles the failure path of a promise. If the handler fn returns an IPromise, it will be hoisted.
  If the handler fn throws, it will produce a rejected promise."
  [promise cb]
  (proto/catch promise cb))

(defn peek
  "Access the success and/or failure path of a promise chain at a specific point in processing. Can be used
  for side effects (or debugging) only. Does not effect the value to be resolved or rejected. If you only want
  to handle one of success/failure path, pass `nil` for the other handler.

  (peek promise nil println)"
  ([promise cb]
   (peek promise
         (comp cb (partial conj [:success]))
         (comp cb (partial conj [:error]))))
  ([promise on-success on-error]
   (proto/then promise
               (comp resolve (partial try* on-success))
               (comp reject (partial try* on-error)))))
