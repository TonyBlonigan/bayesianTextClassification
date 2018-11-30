# bayesianTextClassification

## Detecting Project Gutenberg Headers, Footers &amp; Novels Using Naive Bayes Classification with Spark and Scala

Uses Spark, Scala, and a bit of R for visualization
![](https://github.com/TonyBlonigan/bayesianTextClassification/blob/master/Results.Accuracy.png "Accuracy by N-Gram Length")

## Brief Project Overview
I had access to a 200-core cluster and wanted to play around with spark and scala. I also had access to 4,160 plain-text books from projectgutenberg.org. On reviewing the books, I was disappointed to find that each book started and ended with 'verbiage' added to each novel by the Project Gutenberg editors. To make matters worse, each header/footer was different from the next. It looks to me like there are several templates the editors are using for their headers and footers, and there are also lots of variations of sentences within each template (probably because the editors were changing the templates to their liking).

In any case, I decided to build a naive Bayes classifier to distinguish between headers, footers, and the texts written by authors, because it is a simple algorithm to build from scratch, and I thought it would do a good job with this task. 

I used regular expressions to approximate the borders between classes. Then I split the books into training/test sets (simple 70-30, again because easy to build from scratch). I used the word distributions for each class in the training set to classify the test sets by splitting each book into series of n words (n-grams), then predicting each n-gram belonged to the class with the highest probability mass function. Given the gigantic amount of data, I assumed the priors would be swamped and used flat priors to save coding time.

This project was a quick introduction to scala/spark for me, so there are several improvements that could be made (see TODO list at bottom of bayesianTextClassifier.scala)

## Results
I ran the model with n-gram lengths of between 3 and 25. Accuracy was 95.9%, with just three words to predict on, and up to 99.4% by 15 words. This is mostly because the model is so good at identifying novels, and the novles have much more text than the headers/footers, so most of the train/test observations are novels.

Not surprisingly, most of the model's failures were in distinguishing between the headers and footers. You can see this reflected in the recall and precision charts. I'll spare you the details, but the confusion matrixes are in the plots folder, if you are really interested.

That said, if my goal were really to simply pull out the text of the novel, I suspect that collapsing the header and footer into a single class would lead to extremely high accuracy, recall, and precision.

![](https://github.com/TonyBlonigan/bayesianTextClassification/blob/master/Results.Precision.png "Precision by N-Gram Length")
![](https://github.com/TonyBlonigan/bayesianTextClassification/blob/master/Results.Recall.png "Recall by N-Gram Length")
## Files
* bayesianTextClassifier.scala

This file contains the source code for all spark/scala processing

* Results.*
  
  These files show key evaluation plots so you don't have to dig through the scripts folder

* scripts

This directory contains R scripts used to visualize the data and results, and a plots folder containing more plots. I used a custom R package that I have not shared on github yet (mmmTools) so this script won't really run for you, but shared it so you can at least see how metrics were evaluated
