(ns fulcro-rest-remote.core
  (:refer-clojure :exclude [read])
  (:require #?(:cljs [cljs-http.client :as http])
            [clojure.core.async :as a :refer [<! go]]
            [clojure.spec.alpha :as s]
            [fulcro.client.network :as net]
            [fulcro.client.primitives :as prim]
            [fulcro.util])
  #?(:cljs (:require-macros fulcro-rest-remote.core)))

(def valid-rest-methods
  #{:delete :get :head :jsonp :move :options :patch :post :put})

(def response-dispatch :response-key)

(defmulti read prim/dispatch)

(defmulti mutate prim/dispatch)

(defmulti respond response-dispatch)

(s/def ::endpoint-request
  (s/cat
   :request-name #(= % 'request)
   :request-args (s/and vector? #(= 1 (count %)))
   :request-body (s/* (constantly true))))

(s/def ::endpoint-respond
  (s/cat
   :respond-name #(= % 'respond)
   :respond-args (s/and vector? #(= 1 (count %)))
   :respond-body (s/* (constantly true))))

(s/def ::endpoint-args
  (s/cat
   :identifier (s/or :sym symbol? :kw keyword?)
   :doc (s/? string?)
   :arglist vector?
   :request (s/spec ::endpoint-request)
   :respond (s/spec ::endpoint-respond)))

#?(:clj
   (defmacro ^{:doc      "Define a new rest remote endpoint"
               :arglists '([sym docstring? arglist request respond])}
     defendpoint
     [& args]
     (let [{:keys [identifier
                   doc
                   arglist
                   request
                   respond]}      (fulcro.util/conform! ::endpoint-args args)
           {:keys [request-name
                   request-args
                   request-body]} request
           {:keys [respond-name
                   respond-args
                   respond-body]} respond
           [id-type id]           identifier
           env-symbol             (gensym "env")
           method-id              (case id-type
                                    :kw  'fulcro-rest-remote.core/read
                                    :sym `fulcro-rest-remote.core/mutate)
           request-multimethod    `(defmethod ~method-id ~id [~env-symbol ~'_ ~(first arglist)]
                                     (let [~(first request-args) ~env-symbol]
                                       ~@request-body))
           respond-multimethod    `(defmethod respond ~id [~env-symbol]
                                     (let [~(first respond-args) ~env-symbol]
                                       ~@respond-body))]
       `(do
         ~request-multimethod
         ~respond-multimethod))))

(defrecord RestRemote
    [env request-executor active-requests request-parser response-handler]
  net/FulcroRemoteI
  (transmit [this {::net/keys [edn abort-id ok-handler error-handler]
                   :as        raw-request}]
    (let [keyed-requests      (request-parser env edn)
          keyed-request-chans (into {}
                                    (map (fn [[k {:keys [method] :as request}]]
                                           [k (if (contains? valid-rest-methods method)
                                                (request-executor request)
                                                (if (= method :none)
                                                  {:status 200
                                                   :body   :unimplemented}
                                                  (throw (ex-info "Invalid method" {:method method}))))]))
                                    keyed-requests)
          responses           (atom {})]
      ;; Group the request chans by abort-id
      (when abort-id
        (doseq [[_ request-chan] keyed-request-chans]
          (swap! active-requests update abort-id
                 (fn [abort-chans]
                   (conj (or abort-chans #{}) request-chan)))))
      (go
        ;; Get all the requests
        (doseq [[key request-chan] keyed-request-chans]
          (swap! responses assoc key (<! request-chan)))
        (let [rs                @responses
              handle-response   (fn [[response-key response]]
                                  (let [request          (get keyed-requests response-key)
                                        response-env     (merge env {:request      request
                                                                     :response     response
                                                                     :response-key response-key})
                                        handled-response (response-handler response-env)]
                                    [response-key (:value handled-response)]))
              handled-responses (into {} (map handle-response) rs)]
          (ok-handler {:transaction edn
                       :body        handled-responses})))))
  (abort [this abort-id]
    ))

#?(:cljs
   (defn new-rest-remote
     [{:keys [env request-executor active-requests request-parser response-handler]
       :or   {request-executor http/request
              active-requests  (atom {})
              request-parser   (prim/parser {:read   read
                                             :mutate mutate})
              response-handler respond}}]
     (map->RestRemote {:env              env
                       :request-executor request-executor
                       :active-requests  active-requests
                       :request-parser   request-parser
                       :response-handler response-handler})))
