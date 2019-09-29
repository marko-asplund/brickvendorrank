

# Fetch inventory
```
sbt 'runMain com.practicingtechie.Searcher --part-codes-file data/lego-partnumbers.tsv --fetch-inventory'
```

# Rank vendors 

```
sbt 'runMain com.practicingtechie.Searcher --part-codes-file data/lego-partnumbers.tsv --inventory-file brick-inventory.tsv'
```

brick-inventory-8320490823040900556.tsv

[run-main-0] INFO  c.p.Searcher - *** data written to file ./brick-vendors-16432778356562345279.tsv ***
[run-main-0] WARN  c.p.Searcher - unavailable parts: ==> Set(600163, 2568734)
[success] Total time: 2 s, completed 16 May 2019, 18.45.32