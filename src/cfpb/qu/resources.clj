(ns cfpb.qu.resources
  "RESTful resources for datasets and slices. Uses
Liberator (http://clojure-liberator.github.com/) to handle exposing
the resources.

In Liberator, returning a map from a function adds that map to the
context passed to subsequent functions. We use that heavily in exists?
functions to return the resource that will be presented later."
  (:require
   [clojure.string :as str]
   [taoensso.timbre :as log]
   [liberator.core :refer [defresource request-method-in]]
   [liberator.representation :refer [ring-response]]
   [noir.response :refer [status]]
   [protoflex.parse :refer [parse]]
   [halresource.resource :as hal]
   [clojurewerkz.urly.core :as url :refer [url-like]]
   [lonocloud.synthread :as ->]
   [clj-time.coerce :as coerce]
   [clj-time.core :as time]
   [cfpb.qu.project :refer [project]]
   [cfpb.qu.urls :as urls]
   [cfpb.qu.data :as data]
   [cfpb.qu.views :as views]
   [cfpb.qu.query :as query :refer [params->Query]]))

(defn not-found
  ([] (not-found "Route not found"))
  ([msg]
     (status
      404
      (views/layout-html
       (views/not-found-html msg)))))

(defresource
  ^{:doc "Resource for the collection of datasets."}
  index
  :available-media-types ["text/html" "application/json" "application/xml"]
  :method-allowed? (request-method-in :get)
  :exists? (fn [_] {:datasets (data/get-datasets)})
  :etag (fn [{:keys [datasets representation]}]
          (digest/md5 (str (:media-type representation) (vec datasets))))
  :handle-ok (fn [{:keys [request representation datasets]}]
               (let [resource (hal/new-resource (:uri request))
                     embedded (map (fn [dataset]
                                     (hal/add-properties
                                      (hal/new-resource (urls/dataset-path :dataset (:name dataset)))
                                      (:info dataset))) datasets)
                     resource (reduce #(hal/add-resource %1 "dataset" %2) resource embedded)]
                 (views/index (:media-type representation) resource))))

(defn- concept-data
  "Build the concept data for a dataset from its metadata."
  [{concepts :concepts dataset :name}]
  (->> concepts
       (map (fn [[concept data]]
              (let [concept (name concept)]
                [concept
                 (-> data
                     (assoc :url (urls/concept-path :dataset dataset :concept concept))
                     (dissoc :table))])))
       (into {})))

(defresource
  ^{:doc "Resource for an individual dataset."}
  dataset
  :available-media-types ["text/html" "application/json" "application/xml" "text/javascript"]
  :method-allowed? (request-method-in :get)
  :exists? (fn [{:keys [request]}]
             (let [dataset (get-in request [:params :dataset])
                   metadata (data/get-metadata dataset)]
               (if metadata
                 {:dataset dataset
                  :metadata metadata}
                 [false {:dataset dataset}])))
  :etag (fn [{:keys [dataset metadata representation]}]
          (digest/md5 (str (:media-type representation) dataset metadata)))
  :handle-not-found (fn [{:keys [request representation]}]
                      (let [dataset (get-in request [:params :dataset])
                            message (str "No such dataset: " dataset)]
                        (case (:media-type representation)
                          "text/html" (ring-response (not-found message))
                          message)))
  :handle-ok (fn [{:keys [request dataset metadata representation]}]
               (let [resource (-> (hal/new-resource (:uri request))
                                  (hal/add-link :rel "up" :href (urls/datasets-path))
                                  (hal/add-property :id dataset)
                                  (hal/add-properties (:info metadata)))
                     slices (map (fn [[slice info]]
                                     (-> (hal/new-resource
                                          (urls/slice-query-path :dataset dataset
                                                                   :slice (name slice)))
                                         (hal/add-property :id (name slice))
                                         (hal/add-property
                                          :name
                                          (get-in info [:info :name] (name slice)))
                                         (hal/add-properties info))) (:slices metadata))
                     concepts (map (fn [[concept info]]
                                     (let [table (data/concept-data dataset concept)]
                                       (-> (hal/new-resource
                                            (urls/concept-path :dataset dataset
                                                                 :concept (name concept)))
                                           (hal/add-property :id (name concept))
                                           (hal/add-properties (-> info
                                                                   (dissoc :table)
                                                                   (dissoc :properties)))
                                           (->/when (seq table)
                                             (hal/add-property :table {:data table}))))) (:concepts metadata))
                     resource (reduce #(hal/add-resource %1 "slice" %2) resource slices)
                     resource (reduce #(hal/add-resource %1 "concept" %2) resource concepts)
                     callback (get-in request [:params :$callback])
                     view-map {:callback callback}]
                 (views/dataset (:media-type representation) resource view-map))))

(defn- base-url
  "Derive a base URL from the APP_URL environment variable and either the path-info or uri value from the request scope"
  [request]
  (str (@project :app-url) (or (:path-info request)
                               (:uri request))))

(defresource
  ^{:doc "Resource for an individual concept."}
  concept
  :available-media-types ["text/html" "application/json" "application/xml" "text/javascript"]
  :method-allowed? (request-method-in :get)
  :exists? (fn [{:keys [request]}]
             (let [dataset (get-in request [:params :dataset])
                   concept (get-in request [:params :concept])                   
                   metadata (data/get-metadata dataset)
                   cdata (get-in metadata [:concepts (keyword concept)])]
               (if cdata
                 {:dataset dataset
                  :metadata metadata
                  :concept concept
                  :cdata cdata}
                 [false {:dataset dataset :concept concept}])))
  :etag (fn [{:keys [cdata representation]}]
          (digest/md5 (str (:media-type representation) cdata)))
  :handle-not-found (fn [{:keys [dataset concept request representation]}]
                      (let [message (str "No such concept " concept " in dataset " dataset)]
                        (case (:media-type representation)
                          "text/html" (not-found message)
                          message)))
  :handle-ok (fn [{:keys [dataset concept cdata request representation]}]
               (let [callback (get-in request [:params :$callback])
                     resource (-> (hal/new-resource (:uri request))
                                  (hal/add-link :rel "up" :href (urls/dataset-path :dataset dataset))
                                  (hal/add-property :id concept)
                                  (hal/add-property :dataset dataset)
                                  (hal/add-properties (dissoc cdata :table)))
                     resource (let [table (data/concept-data dataset concept)]
                                (if (empty? table)
                                  resource
                                  (hal/add-property resource :table {:data table})))
                     view-map {:callback callback}]
                 (views/concept (:media-type representation) resource view-map))))

(defresource
  ^{:doc "Resource for the metadata of an individual slice."}
  slice-metadata
  :available-media-types ["text/html" "application/json" "application/xml" "text/javascript"]
  :method-allowed? (request-method-in :get)
  :exists? (fn [{:keys [request]}]
             (let [dataset (get-in request [:params :dataset])
                   metadata (data/get-metadata dataset)
                   slice (get-in request [:params :slice])]
               (if-let [slicedef (get-in metadata [:slices (keyword slice)])]
                 {:dataset dataset
                  :metadata metadata
                  :slice slice                  
                  :slicedef slicedef}
                 [false {:dataset dataset :slice slice}])))
  :etag (fn [{:keys [slicedef representation]}]
          (digest/md5 (str (:media-type representation) slicedef)))  
  :handle-not-found (fn [{:keys [dataset slice request representation]}]
                      (let [message (str "No such slice: " dataset "/" slice)]
                        (case (:media-type representation)
                          "text/html" (not-found message)
                          message)))
  :handle-ok (fn [{:keys [dataset slicedef slice request representation]}]
               (let [callback (get-in request [:params :$callback])
                     resource (-> (hal/new-resource (:uri request))
                                  (hal/add-link :rel "up" :href (urls/dataset-path :dataset dataset))
                                  (hal/add-property :id (str dataset "/" (name slice)))
                                  (hal/add-property :dataset dataset)
                                  (hal/add-property :slice slice)
                                  (hal/add-properties (dissoc slicedef :table :type)))
                     view-map {:callback callback}]
                 (views/slice-metadata (:media-type representation)
                                       resource
                                       view-map))))

(defn- templated-url
  "Build the templated URL for slice queries."
  [base-href clauses]
  (str base-href "?"
       (str/join "&"
                 (map
                  (comp #(str "$" % "={?" % "}") name)
                  clauses))))

(defn- slice-resource
  "Build a HAL resource for a slice."
  [dataset slice request query]
  (let [base-href (base-url request)
        href (url-like (if-let [query-string (:query-string request)]
                         (str base-href "?" query-string)
                         base-href))
        ;; If the query string is malformed, url-like will return nil.
        ;; We prevent that by using the base-href in that case.
        href (or href
                 (url-like base-href))
        result (:result query)
        clauses (map (comp keyword :key) views/clauses)
        page (:page query)]
    (-> (hal/new-resource href)
        (hal/add-link :rel "up" :href (urls/dataset-path :dataset dataset))
        (hal/add-link :rel "query"
                      :href (templated-url base-href clauses)
                      :templated true)
        (hal/add-properties {:dataset dataset
                             :slice (name slice)
                             :computing (= (:data result) :computing)
                             :size (:size result)
                             :total (:total result)
                             :page page
                             :query (select-keys query clauses)
                             :errors (:errors query)
                             :dimensions (:dimensions query)
                             :results (:data result)}))))

(defresource
  ^{:doc "Resource for a query on an individual slice."}
  slice-query
  :available-media-types ["text/html" "text/csv" "application/json" "application/xml" "text/javascript"]
  :method-allowed? (request-method-in :get)
  :exists? (fn [{:keys [request]}]
             (let [dataset (get-in request [:params :dataset])
                   metadata (data/get-metadata dataset)
                   slice (get-in request [:params :slice])]
               (if-let [slicedef (get-in metadata [:slices (keyword slice)])]
                 {:dataset dataset
                  :metadata metadata
                  :slice (keyword slice)})))
  :handle-not-found (fn [{:keys [request representation]}]
                      (let [dataset (get-in request [:params :dataset])
                            slice (get-in request [:params :slice])
                            message (str "No such slice: " dataset "/" slice)]
                        (case (:media-type representation)
                          "text/html" (ring-response (not-found message))
                          message)))
  :handle-ok (fn [{:keys [dataset metadata slice request representation]}]
               (let [headers (:headers request)
                     slicedef (get-in metadata [:slices slice])
                     query (params->Query (:params request) metadata slice)
                     query (query/execute query)
                     resource (slice-resource dataset slice request query)
                     view-map {:base-href (:uri request)
                               :query query
                               :metadata metadata
                               :slicedef slicedef
                               :headers headers
                               :dimensions (:dimensions query)
                               :callback (:callback query)
                               :request request}
                     response (views/slice-query
                               (:media-type representation)
                               resource
                               view-map)]
                 (ring-response
                  (if (query/valid? query)
                    response
                    (assoc response :status 400))))))
