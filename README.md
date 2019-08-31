# vow

A Clojure library that builds a promise abstraction on top of [`clojure.core.async`](https://github.com/clojure/core.async)
channels. Usage will be familiar to anyone used to javascript [Promises](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Guide/Using_promises).

## Usage

Here is an example of what can be done with `vow`.

```clojure
(require '[com.ben-allred.vow.core :as vow])

(-> (vow/resolve 3) ;; creates a promise that resolves to the value `3`
    (vow/then inc) ;; increments the resolved value
    (vow/catch dec) ;; has no effect because the promise is not rejected
    (vow/then vow/reject vow/resolve) ;; flips the success/error status of the promise - just go with it
    (vow/catch inc) ;;increments the now rejected value
    (vow/peek println) ;; prints the value without effecting what is in the promise chain
    (vow/then (comp do-something inc))) ;; calls do-something with `6`
;; [:success 5]
;; => Promise{...}
```

### Creating a Promise

There are four promise constructors.

#### `resolve`

Create a promise that resolves a value.

```clojure
(require '[com.ben-allred.vow.core :as vow])

(vow/resolve :foo)
```

#### `reject`

Creates a promise that rejects a value.     

```clojure
(require '[com.ben-allred.vow.core :as vow])

(vow/reject :foo)
```

#### `ch->prom`

Creates a promise by pulling the first available value off a `core.async` channel.

```clojure
(require '[com.ben-allred.vow.core :as vow])

(vow/reject (async/go (async/<! (async/timeout 1000)) :foo))
```

Takes an optional predicate for determining if the value is a success or a failure. Defaults to success.

```clojure
(require '[com.ben-allred.vow.core :as vow])

(vow/ch->prom (async/go :foo) keyword?)
```

#### `create`

Create is a lower level function that allows you create a promise that resolves or rejects based on any arbitrary
logic you can put in a function. Go nuts.

```clojure
(require '[com.ben-allred.vow.core :as vow])

(vow/create (fn [resolve reject]
              (try (resolve (do-something-sketchy!))
                 (catch SketchyException ex
                   (reject ex)))))
```

If the callback throws an exception before resolving or rejecting, the promise is automatically rejected
with the exception. The above can be written as:

```clojure
(require '[com.ben-allred.vow.core :as vow])

(vow/create (fn [resolve _]
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
(require '[com.ben-allred.vow.core :as vow])

(-> (vow/resolve 3)
    (vow/then on-success) ;; only handles success
    (vow/then on-success on-error)) ;; handles success and error
```

#### `catch`

Handles the failure path of the promise. If the handler returns a promise, it will be hoisted up. If the handler throws
an exception it will become a rejected promise. Otherwise the return value is treated as a resolved promise.

```clojure
(require '[com.ben-allred.vow.core :as vow])

(-> (vow/reject 3)
    (vow/catch on-error))
```

#### `peek`

Provides a view into the current state of the promise chain without effecting it. Useful for side-effect-y things like
clean up actions or debugging. It does not matter what the handler returns, or even if it throws an exception. It has
no effect on the value in the promise chain.

```clojure
(require '[com.ben-allred.vow.core :as vow])

(-> (vow/resolve 3)
    (vow/peek handler)  ;; called with [:success 3]
    (vow/then vow/reject)
    (vow/peek handler)  ;; called with [:error 3]
    (vow/peek on-success on-error) ;; separate handlers for success/error (value is not wrapped in a tuple)
    (vow/peek on-success nil) ;; only handle success
    (vow/peek nil on-error)) ;; only handle error
```

#### `deref`

In `Clojure`, promises are `deref`able. Sorry `Clojurescript`ers. The status (`:success` or `:error`) is returned along
with the value in a tuple-ish vector.

```clojure
(require '[com.ben-allred.vow.core :as vow])

@(vow/resolve :foo)
;; => [:success :foo]

@(vow/reject :bar)
;; => [:error :bar]

(-> (vow/resolve 3)
    (vow/then inc)
    (vow/peek println)
    (vow/then dec)
    deref)
;; [:success 4]
;; => [:success 3]
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
