(ns com.ben-allred.vow.impl.protocol)

(defprotocol IPromise
  (then [this on-success on-error])
  (catch [this cb])
  (finally [this cb]))

(defprotocol IPromiseResult
  (result [this]))
