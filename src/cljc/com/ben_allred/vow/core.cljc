(ns com.ben-allred.vow.core
  "A library to wrap core.async channels with chainable left/right (or success/failure) handling."
  (:refer-clojure :exclude [and await first or peek resolve])
  (:require
    [clojure.core.async :as async]
    [com.ben-allred.vow.impl.chan :as impl.chan]
    [com.ben-allred.vow.impl.protocol :as proto]))

(defn ^:private add-meta [xs]
  #?(:cljs (js/console.log (pr-str (meta (clojure.core/first xs)))))
  (cons (:tag (meta (clojure.core/first xs))) xs))

(defn ^:private invocation? [x]
  (clojure.core/and (sequential? x) (not (vector? x))))

(defn ^:private deref!* [[status value]]
  #?(:clj
     (cond
       (clojure.core/and (= :error status) (instance? Throwable value))
       (throw value)

       (= :error status)
       (throw (ex-info "Failed promise" {:error value}))

       :else
       value)))

(def ^:private wrap-success
  (partial conj [:success]))

(def ^:private wrap-error
  (partial conj [:error]))

(defn promise?
  "Returns `true` if `x` satisfies `IPromise`."
  [x]
  (satisfies? proto/IPromise x))

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

(defn resolve
  "Creates a promise that resolves with `val`."
  ([]
   (resolve nil))
  ([val]
   (create (fn [resolve _] (resolve val)))))

(defn reject
  "Creates a promise that rejects with `err`."
  ([]
   (reject nil))
  ([err]
   (create (fn [_ reject] (reject err)))))

(defn ^:private try* [cb val]
  (try (if (ifn? cb)
         (resolve (cb val))
         (resolve))
       (catch #?(:clj Throwable :cljs :default) _
         (resolve))))

(defn then
  "Handles the success path (or success and failure path) of a promise. If the handler fn returns
  an IPromise, it will be hoisted. If the handler fn throws, it will produce a rejected promise."
  ([promise on-success]
   (then promise on-success reject))
  ([promise on-success on-error]
   (proto/then promise on-success on-error)))

(defmacro vow
  "A macro for creating a promise out of an expression

  (peek (vow (println \"starting\") (/ 17 0)) println)"
  [& body]
  `(create (fn [resolve# reject#]
             (let [[err# result#] (try [nil ~@body]
                                       (catch ~(if (:ns &env) :default 'Throwable) ex#
                                         [ex#]))]
               (cond
                 err# (reject# err#)
                 (promise? result#) (then result# resolve# reject#)
                 :else (resolve# result#))))))

(defn ch->prom
  "Given a core.async channel and an optional success? predicate, creates a promise with the first value pulled off
  the channel. The promise will resolve or reject according to the result of (success? (async/<! ch)). Defaults
  to always resolving."
  ([ch]
   (ch->prom ch (constantly true)))
  ([ch success?]
   (create (fn [resolve reject]
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
      :cljs (create (fn [resolve reject]
                      (.then prom resolve reject)))))
  #?(:clj
     ([prom success?]
      (create (fn [resolve reject]
                (async/go
                  (let [val @prom]
                    (if (success? val)
                      (resolve val)
                      (reject val)))))))))

(defn sleep
  "Creates a promise that resolves after the specified amount of time (in milliseconds)."
  ([ms]
   (sleep nil ms))
  ([value ms]
   (ch->prom (async/go
               (async/<! (async/timeout ms))
               value))))

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
               (fn [result]
                 (then (try* on-success result) (constantly result)))
               (fn [result]
                 (then (try* on-error result) (constantly (reject result)))))))

(defn all
  "Takes a sequence of promises and returns a promise that resolves when all promises resolve, or rejects if any
  promise rejects. `promises` can be a map or sequential collection.

  (-> {:foo (resolve :bar) :baz (resolve :quux)}
      (all)
      (then println)) ;; {:foo :bar :baz :quux}"
  [promises]
  (create (fn [resolve' reject]
            (let [m? (map? promises)]
              (-> promises
                  (cond->> (not m?) (map-indexed vector))
                  (->> (map (fn [[k promise]]
                              [k (then promise identity reject)]))
                       (reduce (fn [chain [k promise]]
                                 (then chain #(then promise
                                                    (partial assoc % k))))
                               (resolve (if m? {} []))))
                  (then resolve'))))))

(defn any
  "Takes a sequence of promises and returns a promise that resolves to the first success
  or rejects with all errors if everything fails. `promises` can be a map or sequential collection.

  (-> {:foo (resolve :bar) :baz (sleep :quux 100)}
      any
      (then println)) ;; :bar"
  [promises]
  (create (fn [resolve reject']
            (let [m? (map? promises)]
              (-> promises
                  (cond->> (not m?) (map-indexed vector))
                  (->> (map (fn [[k promise]]
                              [k (then promise resolve)]))
                       (reduce (fn [chain [k promise]]
                                 (catch chain #(catch promise
                                                      (comp reject (partial assoc % k)))))
                               (reject (if m? {} []))))
                  (catch reject'))))))

(defn first
  "Takes a sequence of promises and resolves or rejects whichever finishes first.

  (-> [(resolve :foo) (sleep :bar 10)]
      first
      (v/peek println)) ;; [:success :foo]"
  [promises]
  (create (fn [resolve reject]
            (doseq [promise promises]
              (then promise resolve reject)))))

(defmacro then->
  "A macro for handing the success path thread via `->`.

  (-> (resolve 3)
      (then-> inc (* 2) (as-> $ (repeat $ $)) (->> (map dec)))
      (peek println nil)) ;; (7 7 7 7 7 7 7 7)"
  [promise & forms]
  (let [forms' (map (fn [form]
                      (let [[f & args] (if (invocation? form) form [form])]
                        `(then (fn [val#] (~f val# ~@args)))))
                    forms)]
    `(-> ~promise ~@forms')))

(defn deref!
  "Deref a promise into a value, or cause it to throw an exception - Clojure only."
  ([prom]
   #?(:clj  (deref!* @prom)
      :cljs (throw (ex-info "promises cannot be deref'd in ClojureScript" {}))))
  ([prom timeout-ms timeout-value]
   #?(:clj  (deref!* (deref prom timeout-ms [:success timeout-value]))
      :cljs (throw (ex-info "promises cannot be deref'd in ClojureScript" {})))))

(defmacro always
  "A macro that always executes a body, regardless of the result of the promise. Evaluates forms in order and
  returns the last one.

  (always my-promise (println \"I always happen\") 17)"
  [promise & body]
  `(letfn [(f# [_#] ~@body)]
     (then ~promise f# f#)))

(defmacro and
  "Continues the promise chain by executing each form in order until one throws or results in a rejected promise.
  Returns the last success or the first rejection."
  [promise & forms]
  (let [forms' (map (fn [form]
                      `(then (fn [_#] ~form)))
                    forms)]
    `(-> ~promise ~@forms')))

(defmacro or
  "Continues the promise chain by executing each form in order until one returns a value or a resolved promise.
  Returns the first success or the last rejection."
  [promise & forms]
  (let [forms' (map (fn [form]
                      `(com.ben-allred.vow.core/catch (fn [_#] ~form)))
                    forms)]
    `(-> ~promise ~@forms')))

(defmacro await
  "A lexical binding macro that coerces it's expressions into promises and binds their results accordingly.
  Any thrown exception or attempt to bind to a rejected promise results in a rejected promise, otherwise it
  resolves the body with the expressions bound.

  (v/await [foo (v/sleep :foo 100)
            _ (println \"this will print after 100 ms\")
            bar (v/or (v/reject :error) (v/resolve :baz))]
    [foo bar]) ;; [:success [:foo :bar]]"
  [bindings & body]
  (assert (clojure.core/and (vector? bindings) (even? (count bindings))) "bindings must be a vector with an even number of forms")
  (if (empty? bindings)
    `(and (resolve) ~@body)
    (let [[[expr frm] & more] (partition 2 (reverse bindings))]
      (reduce (fn [prom [expr frm]]
                `(then (vow ~expr) (fn [~frm] ~prom)))
              `(then (vow ~expr) (fn [~frm] (and (resolve) ~@body)))
              more))))

(defmacro attempt
  "A macro to imitate try/catch/finally behavior with promises.

  (begin (reject \"some error\")
    (catch ^Number num
      [:number num])
    (catch any
      [:any any])
    (catch ^String str
      [:string str])
    (finally
      (println \"finally\"))) ;; [:success [:any \"some error\"]]"
  [& forms]
  (let [[body handlers finale]
        (loop [body [] handlers [] [form :as forms'] forms]
          (cond
            (empty? forms')
            [body handlers]

            (clojure.core/and (invocation? form) (= 'catch (clojure.core/first form)))
            (recur body (conj handlers (add-meta (rest form))) (rest forms'))

            (clojure.core/and (invocation? form) (= 'finally (clojure.core/first form)) (empty? (rest forms')))
            [body handlers (rest form)]

            (clojure.core/and (empty? handlers)
                              (clojure.core/or (not (invocation? form))
                                               (not (#{'catch 'finally} (clojure.core/first form)))))
            (recur (conj body form) handlers (rest forms'))

            :else
            (throw (ex-info "invalid attempt form" {:failed-on form :forms forms}))))
        f (when finale
            `(fn [result#]
               (and (resolve) ~@finale result#)))]
    (cond-> (reduce (fn [promise [tag frm & catch-body]]
                      `(com.ben-allred.vow.core/catch ~promise
                                                      (fn [value#]
                                                        (if (if-let [t# ~tag]
                                                              (clojure.core/or (instance? t# value#)
                                                                               (= t# (type value#)))
                                                              true)
                                                          (let [~frm value#] (and (resolve) ~@catch-body))
                                                          (reject value#)))))
                    `(and (resolve) ~@body)
                    handlers)
      f (as-> promise `(then ~promise ~f (comp reject ~f))))))
