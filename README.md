# How to

## How to run test server

* Put articles in `src/main/resources/input.csv`
* Run test server: `com.example.TestServerApp`
* Fetch products from test server: `curl http://localhost:8081/articles/1000`

## How to run sync app

* Define "product" and "article" endpoints in `src/main/resources/application.conf`
* Run sync app: `sbt run`
* Trigger sync: `curl http://localhost:8080/sync`
