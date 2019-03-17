

# Fetch inventory
```
sbt 'runMain com.practicingtechie.Searcher --part-codes-file data/lego-partnumbers.tsv --fetch-inventory'
```

# Rank vendors 

```
sbt 'runMain com.practicingtechie.Searcher --part-codes-file data/lego-partnumbers.tsv --inventory-file brick-inventory.tsv'
```
