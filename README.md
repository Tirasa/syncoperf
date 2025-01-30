# syncoperf
Performance tools and reports about Apache Syncope

### How to run

1. set a target (for example `-Ppgjsonb -Dbulk.load.users=1000`)
1. run via
    ```
    mvn -Dmaven.build.cache.enabled=false -Ppgjsonb -Dspring-boot.run.profiles=syncoperf,pgjsonb -Dbulk.load.users=1000
    ```
1. generate HTML report via
    ```
    jmeter -g target/jmeter/results/*-heterogeneous.csv -o docs/pgjsonb-1k/
    ```
