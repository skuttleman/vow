(ns com.ben-allred.vow.test.async
  (:require [clojure.core.async :as async]))

(defmacro async [sym ch]
  `(let [~sym (constantly nil)]
     (async/<!! ~ch)))
