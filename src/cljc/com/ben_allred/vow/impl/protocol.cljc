(ns com.ben-allred.vow.impl.protocol)

(defprotocol IPromise
  (then [this on-success on-error]))

(defprotocol IPromiseResult
  (result [this]))
