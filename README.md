# vow

A Clojure library that builds a promise abstraction on top of [`clojure.core.async`](https://github.com/clojure/core.async)
channels. Usage will be familiar to anyone used to javascript [Promises](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Guide/Using_promises).

[![Clojars Project](https://img.shields.io/clojars/v/com.ben-allred/vow.svg)](https://clojars.org/com.ben-allred/vow)

## Usage

Here is an example of what can be done with `vow`.

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
;; [:success 5]
;; => Promise{...}
```

### Creating a Promise

There are six promise constructors.

#### `promise`

A macro for abstracting the creation of a promise. If the body evaluates to a promise, it will be hoisted. Otherwise,
the return value will be resolved, or the a thrown exception will be rejected.

```clojure
(require '[com.ben-allred.vow.core :as v])

(-> (v/promise (+ 1 2))
    (v/then println))
;; 3

(-> (v/promise (throw (ex-info "oh, no!" {:mr :bill})))
    (v/catch ex-data)
    (v/then println))
;; {:mr :bill}

(-> (v/promise (v/reject 17))
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

### `navtive->prom`

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
    (v/peek handler)  ;; called with [:success 3]
    (v/then v/reject)
    (v/peek handler)  ;; called with [:error 3]
    (v/peek on-success on-error) ;; separate handlers for success/error (value is not wrapped in a tuple)
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
