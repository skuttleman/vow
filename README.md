# vow

A Clojure library that builds an asynchronous abstraction on top of [clojure.core.async](https://github.com/clojure/core.async)
channels. The abstraction is based on the notion of left/right handling (success/error) and usage will be familiar to
anyone that has used javascript [Promises](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Guide/Using_promises).

[![Clojars Project](https://img.shields.io/clojars/v/com.ben-allred/vow.svg)](https://clojars.org/com.ben-allred/vow)

## Usage

Here are some examples of what can be done with `vow`.

```clojure
(require '[com.ben-allred.vow.core :as v])

(-> (v/resolve 3) ;; creates a promise that resolves to the value `3`
    (v/then inc) ;; increments the resolved value
    (v/then-> (* 2) (+ 7) (->> (/ 60))) ;; a threading macro for the success path
    (v/catch dec) ;; has no effect because the promise is resolved
    (v/then v/reject v/resolve) ;; flips the success/error status of the promise - just go with it
    (v/then dec) ;; has no effect because the promise is rejected
    (v/catch inc) ;; increments the now rejected value
    (v/peek println) ;; prints the value without effecting what is in the promise chain
    (v/then (comp do-something inc))) ;; calls do-something with `6`
;; => Promise{...}
;; [:success 5]

(v/peek (v/attempt (v/await [a 7
                             b (v/resolve (- a 4))
                             [c d] (v/all [(v/resolve 1) (v/resolve (inc b))])]
                     (v/sleep 100)
                     (v/reject [a b c d]))
                   (catch ^clojure.lang.Keyword k
                      [:keyword k])
                   (catch ^clojure.lang.APersistentVector v
                      (conj v :handled))
                   (finally (println "finally!")))
        println)
;; => Promise{...}
;; finally!
;; [:success [7 3 1 4 :handled]]
```

### Creating a Promise

There are seven promise constructors.

#### `vow`

A macro for abstracting the creation of a promise. If the body evaluates to a promise, it will be hoisted. Otherwise,
it will reject if an exception is thrown or resolve to the return value.

```clojure
(require '[com.ben-allred.vow.core :as v])

(-> (v/vow (+ 1 2))
    (v/then println))
;; 3

(-> (v/vow (throw (ex-info "oh, no!" {:mr :bill})))
    (v/catch ex-data)
    (v/then println))
;; {:mr :bill}

(-> (v/vow (v/reject 17))
    (v/then inc)
    (v/catch println))
;; 17
```

#### `resolve`

Create a promise that resolves a value.

```clojure
(require '[com.ben-allred.vow.core :as v])

(v/resolve :foo)
```

#### `reject`

Creates a promise that rejects a value.     

```clojure
(require '[com.ben-allred.vow.core :as v])

(v/reject :foo)
```

#### `sleep`

Creates a promise that resolves a value after the specified timeout (in milliseconds).
The promise make take longer than the specified timeout, but will not happen earlier.

```clojure
(require '[com.ben-allred.vow.core :as v])

(-> (v/sleep (.getTime (java.util.Date.)) 100)
    (v/then-> (- (.getTime (java.util.Date.))))
    (v/peek println)) ;; [:success -100]

(-> (v/resolve 17)
    (v/then-> (v/sleep 1000) ;; takes value as its first argument to be composable with then->
              println)) ;; 17

(-> (v/sleep (v/reject :bad) 100) ;; if value is a promise, it will be hoisted
    (v/catch println)) ;; :bad
```

#### `navtive->prom`

Converts a "native promise" (in Clojure - any IDeref, in Clojurescript - a js/Promise object) into a promise.
In clojure, takes an optional predicate to determine if the value is a success or not. Defaults to `(constantly true)`.

```clojure
(require '[com.ben-allred.vow.core :as v])

(-> #?(:clj  (doto (promise) (deliver 17))
       :cljs (js/Promise.resolve 17))
    v/native->prom
    (v/then inc)
    (v/then println))
;; 18

(-> #?(:clj  (v/native->prom (doto (promise) (deliver 17)) even?)
       :cljs (v/native->prom (js/Promise.reject 17)))
    (v/then inc)
    (v/catch println))
;; 17
```

#### `ch->prom`

Creates a promise by pulling the first available value off a `core.async` channel.

```clojure
(require '[com.ben-allred.vow.core :as v])

(v/reject (async/go (async/<! (async/timeout 1000)) :foo))
```

Takes an optional predicate for determining if the value is a success or a failure. Defaults to success.

```clojure
(require '[com.ben-allred.vow.core :as v])

(v/ch->prom (async/go :foo) keyword?)
```

#### `create`

Create is a lower level function that allows you create a promise that resolves or rejects based on any arbitrary
logic you can put in a function. Go nuts.

```clojure
(require '[com.ben-allred.vow.core :as v])

(v/create (fn [resolve reject]
            (try (resolve (do-something-sketchy!))
               (catch SketchyException ex
                 (reject ex)))))
```

If the callback throws an exception before resolving, the promise is automatically rejected
with the exception. The above can be written as:

```clojure
(require '[com.ben-allred.vow.core :as v])

(v/create (fn [resolve _]
            (resolve (do-something-sketchy!))))
```

### Using a Promise

Once you have a promise, you can chain it and handle it in a number of ways. All of the handlers create new promises,
and the promises are not mutated.

#### `then`

Handles the success or success and failure path of the promise. If the handler returns a promise, it will be hoisted up.
If the handler throws an exception it will become a rejected promise. Otherwise the return value is treated as a resolved
promise.

```clojure
(require '[com.ben-allred.vow.core :as v])

(-> (v/resolve 3)
    (v/then on-success) ;; only handles success
    (v/then on-success on-error)) ;; handles success and error
```

#### `catch`

Handles the failure path of the promise. If the handler returns a promise, it will be hoisted up. If the handler throws
an exception it will become a rejected promise. Otherwise the return value is treated as a resolved promise.

```clojure
(require '[com.ben-allred.vow.core :as v])

(-> (v/reject 3)
    (v/catch on-error))
```

#### `peek`

Provides a view into the current state of the promise chain without effecting it. Useful for side-effect-y things like
clean up actions or debugging. It does not matter what the handler returns, or even if it throws an exception. It has
no effect on the value in the promise chain.

```clojure
(require '[com.ben-allred.vow.core :as v])

(-> (v/resolve 3)
    (v/peek handler)  ;; called with: [:success 3]
    (v/then v/reject)
    (v/peek handler)  ;; called with: [:error 3]
    (v/peek on-success on-error) ;; separate handlers for success/error on-error is called with: 3
    (v/peek on-success nil) ;; only handle success
    (v/peek nil on-error)) ;; only handle error
```

#### `all`

Takes a collection of promises and returns a promise that resolves to a vector of success values (or map if passed a map).
The resulting value retains the order or promises passed in (or associated keys if passed a map).

```clojure
(require '[com.ben-allred.vow.core :as v])

(-> [(v/ch->prom (async/go
                   (async/<! (async/timeout 1000))
                   :foo))
     (v/resolve :bar)]
     (v/all)
     (v/then println)) ;; [:foo :bar]

(-> {:foo (v/ch->prom (async/go
                        (async/<! (async/timeout 1000))
                        :bar))
     :baz (v/resolve :quux)}
     (v/all)
     (v/then println)) ;; {:foo :bar :baz :quux}
```

If any promise fails, the promise will reject with the first error processed.

```clojure
(require '[com.ben-allred.vow.core :as v])

(-> [(v/resolve :foo) (v/reject :bar) (v/resolve :baz) (v/reject :quux)]
    (v/all)
    (v/catch println)) ;; :bar
```

### `any`

Takes a collection of promises and returns the first success or a vector of errors (or map if passed a map) if they all fail.

```clojure
(require '[com.ben-allred.vow.core :as v])

(-> [(v/sleep :foo 50) (v/reject :bar) (v/sleep :baz 100)]
    v/any
    (v/peek println)) ;; [:success :foo]

(-> {:foo (v/reject :bar) :baz (v/reject :quux)}
    v/any
    (v/peek println)) ;; [:error {:foo :bar :baz :quux}]
```

#### `first`

Takes a collection of promises and resolves or rejects whichever finishes first.

```clojure
(require '[com.ben-allred.vow.core :as v])

(-> [(v/vow (long-running-process))
     (v/sleep (v/reject :timed-out) 5000)]
    v/first
    (v/then handle-finished handle-error-or-timeout))
```

#### `deref`

In `Clojure`, promises are `deref`able (sorry `ClojureScript`ers). The status (`:success` or `:error`) is returned along
with the value in a tuple-ish vector.

```clojure
(require '[com.ben-allred.vow.core :as v])

;; Clojure only!

@(v/resolve :foo)
;; => [:success :foo]

@(v/reject :bar)
;; => [:error :bar]

(-> (v/resolve 3)
    (v/then inc)
    (v/peek println)
    (v/then dec)
    deref)
;; [:success 4]
;; => [:success 3]
```

#### `deref!`

A helper function that either returns the success value, or throws an exception.

```clojure
(require '[com.ben-allred.vow.core :as v])

(v/deref! (v/resolve :foo))
;; => :foo

(let [ex (ex-info "an exception" {:some :data})]
  (try (v/deref! (v/reject ex))
       (catch Throwable ex'
         (= ex ex'))))

;; => true

(try (v/deref! (v/reject :bar))
     (catch Throwable ex
       (ex-data ex)))
;; => {:error :bar}
```

### Convenience macros

#### `then->`

A macro for threading happy path actions via `->`.

```clojure
(require '[com.ben-allred.vow.core :as v])

(-> (v/resolve 3)
    (v/then-> (* 2) v/reject)
    (v/catch dec)
    (v/then-> (* 3) println)) ;; 15

(-> (v/reject 3)
    (v/then-> (* 2) v/reject)
    (v/catch dec)
    (v/then-> (* 3) println)) ;; 6
```

#### `attempt`

A macro that uses try/catch semantics to express promise handling.

```clojure
(require '[com.ben-allred.vow.core :as v])

(v/peek (v/attempt (throw (ex-info "foo" {:bar :baz}))
                   (println "I never happen")
                   (catch ^clojure.lang.Keyword k ;; optional runtime type-based dispatch via metadata
                     [:keyword k])
                   (catch ^clojure.lang.IExceptionInfo ex
                     [:caught (ex-data ex)]) ;; exception is caught here
                   (catch any ;; would handle any rejection if it hadn't already been caught
                     (println "re-throwing")
                     (v/reject any))
                   (finally
                     (println "I always happen")))
        println)
;; I always happen
;; [:success [:caught {:bar :baz}]]
```

#### `await`

A macro for expressing promise handling as lexical bindings.

```clojure
(require '[com.ben-allred.vow.core :as v])

(v/peek (v/await [a (v/resolve 7)
                  {:keys [b c]} (v/sleep {:b 4 :c 17} 100)
                  d (+ a b c)]
          (v/sleep 100)
          {:double (* d 2)
           :half   (/ d 2)})
        println)
;; [:success {:double 56 :half 14}]

(v/peek (v/await [a (+ 1 2)
                  _ (v/reject [:bad a])]
          [:good a])
        println)
;; [:error [:bad 3]]
```

Only top-level promises in binding expression and the body of await will be waited upon. The following *does not work*.

```clojure
(require '[com.ben-allred.vow.core :as v])

;; does NOT evaluate nested promises before attempting to add them
(v/await [a (v/resolve 7)]
  (println (v/sleep :foo 100)) ;; BAD -> prints the promise instead of awaiting it
  (+ a (v/resolve 9) (v/resolve 2))) ;; BAD -> attempts to add promises

;; use explicit bindings instead
(v/await [a (v/resolve 7)
          [b c] (v/all [(v/resolve 9) (v/resolve 2)])
          foo (v/sleep :foo 100)]
  (println foo)
  (+ a b c))
```

#### `always`

A macro that executes the body once the promise is finished, without regard for whether it resolved or what its
value or error was.

```clojure
(require '[com.ben-allred.vow.core :as v])

(-> (v/resolve 3)
    (v/always (throw (ex-info "force failure" {:foo :bar})))
    (v/catch (comp println ex-data))) ;; {:foo :bar}

(-> (v/reject 3)
    (v/always (v/resolve 17))
    (v/then println)) ;; 17
```

#### `and`

A macro for continuing a chain of promises as long as they resolve. Promises happen in order (if at all).
It short circuits like `clojure.core/and`. Resolves to the last result, or the first rejection.

```clojure
(require '[com.ben-allred.vow.core :as v])

(-> (v/resolve)
    (v/and (println "before") ;; before
           (write-to-the-db!)
           (println "after") ;; after
           (notify-subscribers!)
           (+ 1 2))
    (v/peek println)) ;; [:success 3]

(-> (v/resolve)
    (v/and (println "this happens") ;; this happens
           (v/reject :stop)
           (println "this does not happen"))
    (v/peek println)) ;; [:error :stop]
```

#### `or`

A macro for continuing a chain of promises as long as they reject. Promises happen in order (if at all).
It short circuits like `clojure.core/or`. Resolves to the last error, or the first resolution.

```clojure
(require '[com.ben-allred.vow.core :as v])

(-> (v/reject)
    (v/or (v/reject (println "before")) ;; before
          (throw (ex-info "bad" {}))
          (v/reject (println "after")) ;; after
          (v/reject :final))
    (v/peek println)) ;; [:error :final]

(-> (v/reject)
    (v/or (v/reject (println "this happens")) ;; this happens
          (v/resolve :done)
          (v/reject (println "this does not happen")))
    (v/peek println)) ;; [:success :done]
```

## Development

### Run Tests

The tests are run in Clojure and Clojurescript (NodeJS)

```bash
$ lein test && lein doo once
```

### Deploy

Update the version in `project.clj` and deploy to clojars.

```bash
$ lein deploy clojars
```
## License

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
