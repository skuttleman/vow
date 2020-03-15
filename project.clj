(defproject com.ben-allred/vow "0.3.0"
  :description "A JS-like promise interface built on top of core.async channels"
  :url "https://github.com/skuttleman/vow"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :source-paths ["src/cljc"]
  :test-paths ["test/cljc"]
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/clojurescript "1.10.597"]
                 [org.clojure/core.async "1.0.567"]]
  :plugins [[cider/cider-nrepl "0.21.1"]
            [lein-cljsbuild "1.1.7"]])
