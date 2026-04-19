Scenario - Let's say, an offer is being published as part of the catalog without pointing to any resources, i.e. the offer is not linked to any product or service. 

While the offer has provider object right? Which means this offer is applicable to all the resources of the provider by default. 

- Catalog will just store this catalog object into a git. 
- deliver this catalog to discover service 
- Once this offer being read by discover service 
    - We may need to implement a offer table in discover service to store such offer only (not linked to any resources)
   - When a discover or search happened 
        - either on the spatial search
        - jsonpath search 
        - text search 
        - in feature it can be any 
    If in the result it would be array of catalogs right?
        - And in catalog it will be having a providerId right? 
            For that provider you need to resolve if there are any global offers from the offer table and append to offer object in the response
            
             

