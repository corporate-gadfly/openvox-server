(ns puppetlabs.services.master.master-service-otel-test
  "Tests for OpenTelemetry (OTEL) metrics integration in the master service.
  Validates that:
    - OTEL histogram is created and stored in the service context
    - OTEL middleware records http.server.request.duration with dimensional attributes
    - The /metrics/v3 endpoint serves Prometheus text exposition format
    - OTEL is disabled by default (no histogram when config absent)
    - get-otel-meter-provider returns MeterProvider or nil based on config"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [clojure.string :as str]
    [cheshire.core :as json]
    [puppetlabs.http.client.sync :as http-client]
    [puppetlabs.kitchensink.core :as ks]
    [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap-testutils]
    [puppetlabs.puppetserver.testutils :as testutils]
    [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
    [puppetlabs.trapperkeeper.app :as tk-app]
    [puppetlabs.trapperkeeper.services :as tk-services]
    [puppetlabs.trapperkeeper.services.protocols.metrics :as metrics-protocol]
    [puppetlabs.trapperkeeper.testutils.logging :as logutils]
    [schema.test :as schema-test])
  (:import (io.opentelemetry.api.metrics DoubleHistogram MeterProvider)
           (io.opentelemetry.sdk.metrics SdkMeterProvider)))

(def test-resources-path "./dev-resources/puppetlabs/services/master/master_service_test")
(def test-resources-code-dir (str test-resources-path "/codedir"))
(def test-resources-conf-dir (str test-resources-path "/confdir"))

(def master-service-test-runtime-dir "target/master-service-test")

(def gem-path
  [(ks/absolute-path jruby-testutils/gem-path)])

(def otel-enabled-config
  "OTEL is enabled by default — no explicit config needed."
  {:jruby-puppet {:gem-path gem-path
                  :max-active-instances 1
                  :server-code-dir test-resources-code-dir
                  :server-conf-dir master-service-test-runtime-dir}
   :metrics {:server-id "localhost"}})

(def otel-disabled-config
  {:jruby-puppet {:gem-path gem-path
                  :max-active-instances 1
                  :server-code-dir test-resources-code-dir
                  :server-conf-dir master-service-test-runtime-dir}
   :metrics {:server-id "localhost"
             :opentelemetry {:enabled false}
             :metrics-webservice {:opentelemetry {:enabled false}}}})

(use-fixtures :once
              schema-test/validate-schemas
              (fn [f]
                (testutils/with-config-dirs
                 {test-resources-conf-dir
                  master-service-test-runtime-dir}
                 (f))))

(use-fixtures :each
              #(logutils/with-test-logging (%)))

(defn http-get
  [url]
  (let [ssl-dir (str master-service-test-runtime-dir "/ssl")]
    (http-client/get (str "https://localhost:8140" url)
                     {:ssl-cert    (str ssl-dir "/certs/localhost.pem")
                      :ssl-key     (str ssl-dir "/private_keys/localhost.pem")
                      :ssl-ca-cert (str ssl-dir "/certs/ca.pem")
                      :headers     {"Accept" "application/json"}
                      :as          :text})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Helpers

(defn parse-prometheus-count
  "Extract the count value from a Prometheus _count line matching the given label pattern.
  Returns the count as a long, or nil if not found."
  [body label-pattern]
  (when-let [match (re-find (re-pattern (str "http_server_request_duration_count\\{[^}]*" label-pattern "[^}]*\\}\\s+(\\d+)")) body)]
    (Long/parseLong (second match))))

(defn count-prometheus-lines
  "Count lines in the Prometheus body matching the given regex pattern."
  [body pattern]
  (->> (str/split-lines body)
       (filter #(re-find (re-pattern pattern) %))
       count))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(deftest ^:integration otel-histogram-stored-in-service-context
  (testing "When OTEL is enabled, otel-histogram is created and stored in the master service context"
    (bootstrap-testutils/with-puppetserver-running
     app
     otel-enabled-config
     (let [master-service (tk-app/get-service app :MasterService)
           svc-context    (tk-services/service-context master-service)
           otel-histogram (:otel-histogram svc-context)]
       (is (some? otel-histogram)
           "OTEL histogram should be present in service context when OTEL is enabled")
       (is (instance? DoubleHistogram otel-histogram)
           "OTEL histogram should be a DoubleHistogram instance")
       (is (instance? SdkMeterProvider
                      (metrics-protocol/get-otel-meter-provider
                       (tk-app/get-service app :MetricsService)))
           "When OTEL is enabled, the MeterProvider should be an SdkMeterProvider")))))

(deftest ^:integration otel-histogram-noop-when-disabled
  (testing "When OTEL is disabled, otel-histogram is a noop (zero-overhead) instrument"
    (bootstrap-testutils/with-puppetserver-running
     app
     otel-disabled-config
     (let [master-service (tk-app/get-service app :MasterService)
           svc-context    (tk-services/service-context master-service)
           otel-histogram (:otel-histogram svc-context)]
       ;; When OTEL is disabled, the noop MeterProvider still produces noop
       ;; histogram instruments — they are non-nil but have zero overhead.
       (is (some? otel-histogram)
           "OTEL histogram should be a noop instrument (not nil) when OTEL is disabled")
       (is (instance? DoubleHistogram otel-histogram)
           "Noop histogram should still implement DoubleHistogram")))))

(deftest ^:integration otel-metrics-recorded-on-http-requests
  (testing "OTEL http.server.request.duration is recorded and visible on /metrics/v3"
    (bootstrap-testutils/with-puppetserver-running
     app
     otel-enabled-config

     ;; Make a request to generate OTEL metrics
     (logutils/with-test-logging
      (let [node-response (logutils/with-test-logging
                           (http-get "/puppet/v3/node/foo?environment=production"))
            node-body     (-> node-response :body (json/parse-string true))]
        (is (= 200 (:status node-response)))
        (is (= "foo" (:name node-body))
            "Node response body should contain the requested certname")
        (is (= "production" (:environment node-body))
            "Node response body should contain the requested environment")))

     ;; Scrape the /metrics/v3 Prometheus endpoint
     (let [v3-response (http-get "/metrics/v3")]
       (is (= 200 (:status v3-response))
           "/metrics/v3 endpoint should return 200")

       (let [body         (:body v3-response)
             content-type (get-in v3-response [:headers "content-type"])]
         (testing "Response is Prometheus text exposition format"
           (is (str/includes? content-type "text/plain")
               "Content-Type should be text/plain (Prometheus format)")
           (is (str/includes? content-type "version=0.0.4")
               "Content-Type should include Prometheus version 0.0.4"))

         (testing "http.server.request.duration histogram is present"
           (is (str/includes? body "http_server_request_duration")
               "Should contain http_server_request_duration metric"))

         (testing "Prometheus format includes HELP and TYPE lines"
           (is (str/includes? body "# HELP http_server_request_duration")
               "Should have HELP line for http_server_request_duration")
           (is (str/includes? body "# TYPE http_server_request_duration histogram")
               "Should have TYPE histogram line"))

         (testing "Dimensional attributes are present as labels"
           (is (re-find #"http_request_method=\"GET\"" body)
               "Should have http.request.method=GET label")
           (is (re-find #"http_response_status_code=\"200\"" body)
               "Should have http.response.status_code=200 label")
           (is (re-find #"http_route=" body)
               "Should have http.route label"))

         (testing "Histogram has _count, _sum, and _bucket lines"
           (is (re-find #"http_server_request_duration_count\{" body)
               "Should have _count lines")
           (is (re-find #"http_server_request_duration_sum\{" body)
               "Should have _sum lines")
           (is (re-find #"http_server_request_duration_bucket\{.*le=\"\+Inf\"" body)
               "Should have a +Inf bucket boundary"))

         (testing "Duration sum is a positive number (request took >0 ms)"
           (let [sum-match (re-find #"http_server_request_duration_sum\{[^}]*\}\s+([0-9.]+)" body)]
             (is (some? sum-match)
                 "Should find a _sum value")
             (when sum-match
               (is (< 0.0 (Double/parseDouble (second sum-match)))
                   "Sum of request durations should be positive")))))))))

(deftest ^:integration otel-v3-endpoint-disabled-by-default
  (testing "The /metrics/v3 endpoint returns 404 when opentelemetry is not enabled"
    (bootstrap-testutils/with-puppetserver-running
     app
     otel-disabled-config
     (let [v3-response (http-get "/metrics/v3")]
       (is (= 404 (:status v3-response))
           "/metrics/v3 should return 404 when OTEL is not enabled"))
     (testing "Other metrics endpoints still work when OTEL is disabled"
       (let [v2-response (http-get "/metrics/v2")]
         (is (not= 404 (:status v2-response))
             "/metrics/v2 should still be available"))))))

(deftest ^:integration otel-metrics-multiple-requests-accumulate
  (testing "Multiple requests accumulate in the OTEL histogram"
    (bootstrap-testutils/with-puppetserver-running
     app
     otel-enabled-config

     ;; Make multiple requests to different endpoints
     (logutils/with-test-logging
      (let [node-resp1 (http-get "/puppet/v3/node/foo?environment=production")
            node-resp2 (http-get "/puppet/v3/node/bar?environment=production")]
        (is (= 200 (:status node-resp1)) "First node request should succeed")
        (is (= 200 (:status node-resp2)) "Second node request should succeed"))
      (logutils/with-test-logging
       (let [catalog-resp (http-get "/puppet/v3/catalog/foo?environment=production")]
         (is (= 200 (:status catalog-resp)) "Catalog request should succeed"))))

     ;; Verify the accumulated metrics
     (let [v3-response (http-get "/metrics/v3")
           body        (:body v3-response)]
       (is (= 200 (:status v3-response)))

       (testing "Histogram shows sum and count lines"
         (is (re-find #"http_server_request_duration_count\{" body)
             "Should have _count lines")
         (is (re-find #"http_server_request_duration_sum\{" body)
             "Should have _sum lines"))

       (testing "Histogram has bucket boundaries"
         (is (re-find #"http_server_request_duration_bucket\{.*le=\"" body)
             "Should have _bucket lines with le labels")
         (is (re-find #"http_server_request_duration_bucket\{.*le=\"\+Inf\"" body)
             "Should have +Inf bucket"))

       (testing "Multiple distinct routes appear in the output"
         (is (re-find #"http_route=\"puppet-v3-node-/\*/\"" body)
             "Should have the node route in labels")
         (is (re-find #"http_route=\"puppet-v3-catalog-/\*/\"" body)
             "Should have the catalog route in labels"))

       (testing "Count values reflect the number of requests made"
         (let [node-count    (parse-prometheus-count body "puppet-v3-node")
               catalog-count (parse-prometheus-count body "puppet-v3-catalog")]
           (is (some? node-count) "Should find node route _count")
           (is (some? catalog-count) "Should find catalog route _count")
           (when node-count
             (is (= 2 node-count)
                 "Node route should have count of 2 (two node requests)"))
           (when catalog-count
             (is (= 1 catalog-count)
                 "Catalog route should have count of 1 (one catalog request)"))))

       (testing "Multiple bucket lines exist per metric series"
         (is (> (count-prometheus-lines body "http_server_request_duration_bucket") 2)
             "Should have multiple bucket boundary lines"))))))

(deftest ^:integration otel-get-otel-meter-provider-returns-provider-when-enabled
  (testing "get-otel-meter-provider returns a MeterProvider when OTEL is enabled"
    (bootstrap-testutils/with-puppetserver-running
     app
     otel-enabled-config
     (let [metrics-service (tk-app/get-service app :MetricsService)
           meter-provider  (metrics-protocol/get-otel-meter-provider metrics-service)]
       (is (some? meter-provider)
           "get-otel-meter-provider should return a non-nil MeterProvider")
       (is (instance? MeterProvider meter-provider)
           "Should be a MeterProvider instance")
       (is (instance? SdkMeterProvider meter-provider)
           "Should be an SdkMeterProvider when OTEL is enabled")))))

(deftest ^:integration otel-get-otel-meter-provider-returns-noop-when-disabled
  (testing "get-otel-meter-provider returns a noop MeterProvider when OTEL is disabled"
    (bootstrap-testutils/with-puppetserver-running
     app
     otel-disabled-config
     (let [metrics-service (tk-app/get-service app :MetricsService)
           meter-provider  (metrics-protocol/get-otel-meter-provider metrics-service)]
       (is (some? meter-provider)
           "get-otel-meter-provider should return a noop MeterProvider, not nil")
       (is (instance? MeterProvider meter-provider)
           "Should be a MeterProvider instance")
       ;; Noop provider is DefaultMeterProvider, not SdkMeterProvider
       (is (not (instance? SdkMeterProvider meter-provider))
           "Should NOT be an SdkMeterProvider when OTEL is disabled")))))
