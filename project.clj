(defproject validaze "1.0.0"
  :description "Hiccup-inspired DSL implementation for the validation of JSON data."
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [com.gfredericks/test.chuck "0.2.8"]
                 [clj-time "0.14.2"]
                 [com.rpl/specter "1.0.4"]
                 [org.clojure/data.codec "0.1.0"]
                 [org.clojure/core.match "0.3.0-alpha5"]
                 [mvxcvi/arrangement "1.1.1"]
                 ;; require 0.10.0 to fix a bug around monkey patching with clojure.test
                 [org.clojure/test.check "0.10.0-alpha2"]]
  :exact-lein-version "2.8.1"
  :main ^:skip-aot validaze.core
  :target-path "target/%s"
; :uberjar-name "lambda.jar"
  ;; print warning if test takes longer than 5 seconds to run
  :eftest {:test-warn-time 5000}
; :plugins [[s3-wagon-private "1.3.1"]]
  :profiles {:uberjar {:aot :all}
             :dev     {:global-vars  {*warn-on-reflection* false}
                       :plugins [[lein-eftest "0.4.1"]]
;                      :jvm-opts ["-Xmx2g"]
                       }}
;  :repositories [["yummly-s3" {:url           "s3p://yummly-repo/releases/"
;                               :no-auth       true
;                               :sign-releases false
;                               :snapshots     {:update :always}}]]
; :mirrors {"central"  {:name      "Nexus"
;                       :url       "https://nexus.yummly.com/content/groups/public"
;                       :snapshots true
;                       :update    :daily}
;           #"clojars" {:name         "Nexus"
;                       :url          "https://nexus.yummly.com/content/groups/public"
;                       :repo-manager true
;                       :snapshots    true
;                       :update       :daily}}
  )
