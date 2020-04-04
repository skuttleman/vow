(ns com.ben-allred.vow.test.runner
  (:require
    [doo.runner :refer-macros [doo-tests]]
    com.ben-allred.vow.core-test))

(doo-tests 'com.ben-allred.vow.core-test)
