(ns com.ben-allred.vow.core
  "A library to wrap core.async channels with chainable left/right (or success/failure) handling."
  (:refer-clojure :exclude [#?(:clj promise) next peek resolve])
  (:require
    [clojure.core.async :as async]
    [com.ben-allred.vow.impl.chan :as impl.chan]
    [com.ben-allred.vow.impl.protocol :as proto]))

(defn ^:private try* [cb val]
  (try (when (ifn? cb)
         (cb val))
       (catch #?(:clj Throwable :cljs :default) _))
  val)

(defn ^:private deref!* [[status value]]
  #?(:clj
     (cond
       (and (= :error status) (instance? Throwable value))
       (throw value)

       (= :error status)
       (throw (ex-info "Failed promise" {:error value}))

       :else
       value)))

(def ^:private wrap-success
  (partial conj [:success]))

(def ^:private wrap-error
  (partial conj [:error]))

(defn resolve
  "Creates a promise that resolves with `val`."
  ([]
   (resolve nil))
  ([val]
   (impl.chan/create (fn [resolve _] (resolve val)))))

(defn reject
  "Creates a promise that rejects with `err`."
  ([]
   (reject nil))
  ([err]
   (impl.chan/create (fn [_ reject] (reject err)))))

(defn ch->prom
  "Given a core.async channel and an optional success? predicate, creates a promise with the first value pulled off
  the channel. The promise will resolve or reject according to the result of (success? (async/<! ch)). Defaults
  to always resolving."
  ([ch]
   (ch->prom ch (constantly true)))
  ([ch success?]
   (impl.chan/create (fn [resolve reject]
                       (async/go
                         (let [val (async/<! ch)]
                           (if (success? val)
                             (resolve val)
                             (reject val))))))))

(defn native->prom
  "Given a \"native\" promise (js/Promise in cljs and anything that implements IDeref in clj), creates a promise
  that resolves or rejects. In cljs it follows js/Promise semantics for resolving and rejecting. In clj you can pass
  an optional `success?` predicate that determines whether to resolve or reject the value which always resolves by
  default."
  ([prom]
   #?(:clj  (native->prom prom (constantly true))
      :cljs (impl.chan/create (fn [resolve reject]
                                (.then prom resolve reject)))))
  #?(:clj
     ([prom success?]
      (impl.chan/create (fn [resolve reject]
                          (async/go
                            (let [val @prom]
                              (if (success? val)
                                (resolve val)
                                (reject val)))))))))

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
  (proto/then promise resolve cb))

(defn peek
  "Access the success and/or failure path of a promise chain at a specific point in processing. Can be used
  for side effects (or debugging) only. Does not effect the value to be resolved or rejected. If you only want
  to handle one of success/failure path, pass `nil` for the other handler.

  (peek promise nil println)"
  ([promise cb]
   (peek promise
         (comp cb wrap-success)
         (comp cb wrap-error)))
  ([promise on-success on-error]
   (proto/then promise
               (comp resolve (partial try* on-success))
               (comp reject (partial try* on-error)))))

(defn all
  "Takes a sequence of promises and returns a promise that resolves when all promises resolve, or rejects if any
  promise rejects. `promises` can be a map or sequential collection

  (-> {:foo (resolve :bar) :baz (resolve :quux)}
      (all)
      (then println)) ;; {:foo :bar :baz :quux}"
  [promises]
  (let [m? (map? promises)]
    (reduce (fn [result-promise [k promise]]
              (then result-promise (fn [results]
                                     (then promise (partial assoc results k)))))
            (resolve (if m? {} []))
            (cond->> promises
              (not m?) (map-indexed vector)))))

(defn promise?
  "Returns `true` if `x` satisfies `IPromise`."
  [x]
  (satisfies? proto/IPromise x))

(defmacro then->
  "A macro for handing the success path thread via `->`.

  (-> (resolve 3)
      (then-> inc (* 2) (as-> $ (repeat $ $)) (->> (map dec)))
      (peek println nil)) ;; (7 7 7 7 7 7 7 7)"
  [promise & forms]
  (let [forms' (map (fn [form]
                      (let [[f & args] (if (list? form) form [form])]
                        `(then (fn [val#] (~f val# ~@args)))))
                    forms)]
    `(-> ~promise ~@forms')))

(defmacro promise
  "A macro for creating a promise out of an expression

  (peek (promise (println \"starting\") (/ 17 0)) println)"
  [& body]
  `(create (fn [resolve# reject#]
             (let [[err# result#] (try [nil ~@body]
                                       (catch ~(if (:ns &env) :default 'Throwable) ex#
                                         [ex# nil]))]
               (cond
                 err# (reject# err#)
                 (promise? result#) (then result# resolve# reject#)
                 :else (resolve# result#))))))

(defn deref!
  ([prom]
   #?(:clj  (deref!* @prom)
      :cljs (throw (ex-info "promises cannot be deref'd in ClojureScript" {}))))
  ([prom timeout-ms timeout-value]
   #?(:clj  (deref!* (deref prom timeout-ms [:success timeout-value]))
      :cljs (throw (ex-info "promises cannot be deref'd in ClojureScript" {})))))
