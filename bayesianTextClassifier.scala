

/**
  * @author Tony Blonigan
*/
  
  import org.apache.spark.{ SparkConf, SparkContext }

object bayesianTextClassifier {
  
  var sc: SparkContext = _
  
  def main(args: Array[String]) {
    // run checks and setup spark
    if (args.length < 4) {
      println("Usage: bayesianTextClassifier <input> <output> <numOutputFiles> <nGramLength>")
      System.exit(1)
    }
    
    val sparkConf = new SparkConf().setAppName("bayesianTextClassifier")
    val sc = new SparkContext(sparkConf)  
    
    
    // get the texts we will work with --------------------------------------------
      val input = sc.wholeTextFiles(args(0)).filter(x => acceptPageCountClassify(x._2)).map(x => (cleanseFileName(x._1), formatNovel(x._2)))
    
    val books: org.apache.spark.rdd.RDD[(String, String)] = input.map(x => (x._1, getNovel(x._2)))
    
    val headers: org.apache.spark.rdd.RDD[(String, String)] = input.map(x => (x._1, getHeader(x._2)))
    
    val footers: org.apache.spark.rdd.RDD[(String, String)] = input.map(x => (x._1, getFooter(x._2)))
    
    
    // split texts into train and test sets ----------------------------------------
      
      // create list of training and testing doc file names
    val allFiles = headers.map(_._1).collect
    val testFilesLocal = headers.map(_._1).sample(withReplacement = false, fraction = .3, seed = 123).collect()
    val testFiles = sc.parallelize(testFilesLocal)
    val trainFiles = sc.parallelize(allFiles.diff(testFilesLocal))  
    
    
    // create the training sets
    val trainBooks: org.apache.spark.rdd.RDD[String] = trainFiles.map(x => (x,"test")).join(books).map(_._2._2)
    
    val trainHeaders: org.apache.spark.rdd.RDD[String] = trainFiles.map(x => (x,"test")).join(headers).map(_._2._2)
    
    val trainFooters: org.apache.spark.rdd.RDD[String] = trainFiles.map(x => (x,"test")).join(footers).map(_._2._2)
    
    
    // create the testing sets, split them into word counts of their n-grams   
    val testHeaders: org.apache.spark.rdd.RDD[Array[(String, Int)]] = 
      toTestSet(fileNames=testFiles, texts=headers, nGramLength = args(3).toInt)
    
    val testBooks: org.apache.spark.rdd.RDD[Array[(String, Int)]] = 
      toTestSet(fileNames=testFiles, texts=books, nGramLength = args(3).toInt)
    
    val testFooters: org.apache.spark.rdd.RDD[Array[(String, Int)]] = 
      toTestSet(fileNames=testFiles, texts=footers, nGramLength = args(3).toInt)
    
    
    // create training set word distribution histograms ---------------------------------- 
      // for each class (novel, header, footer) create word count for books
    val bookWords = trainBooks.flatMap(x => wordCount(x)).reduceByKey(_+_)
    // get total count of words for books
    val bookWordsCount = bookWords.map(_._2).sum.toDouble
    // generate final distribution
    val trainBookWordsDist: org.apache.spark.broadcast.Broadcast[Array[(String, Double)]] = 
      sc.broadcast(bookWords.map(x => (x._1, x._2 / bookWordsCount)).collect()) 
    
    val headerWords = trainHeaders.flatMap(x => wordCount(x)).reduceByKey(_+_)
    val headerWordsCount = headerWords.map(_._2).sum.toDouble
    val trainHeaderWordsDist: org.apache.spark.broadcast.Broadcast[Array[(String, Double)]] = 
      sc.broadcast(headerWords.map(x => (x._1, x._2 / headerWordsCount)).collect())
    
    val footerWords = trainFooters.flatMap(x => wordCount(x)).reduceByKey(_+_)
    val footerWordsCount = footerWords.map(_._2).sum.toDouble
    val trainFooterWordsDist: org.apache.spark.broadcast.Broadcast[Array[(String, Double)]] = 
      sc.broadcast(footerWords.map(x => (x._1, x._2 / footerWordsCount)).collect())
    
    // classify the test set -------------------------------------------------
      val testedHeaders: Array[Int] = testHeaders.map(x => classify(obs = x, headerDist = trainHeaderWordsDist.value,
                                                                    novelDist = trainBookWordsDist.value, footerDist = trainFooterWordsDist.value)).collect()
    
    val testedNovels: Array[Int] = testBooks.map(x => classify(obs = x, headerDist = trainHeaderWordsDist.value,
                                                               novelDist = trainBookWordsDist.value, footerDist = trainFooterWordsDist.value)).collect()
    
    val testedFooters: Array[Int] = testFooters.map(x => classify(obs = x, headerDist = trainHeaderWordsDist.value,
                                                                  novelDist = trainBookWordsDist.value, footerDist = trainFooterWordsDist.value)).collect()
    
    
    // summarize the results in a confusion matrix ----------------------------
      val confusionMatrix = getConfusionMatrix(headerResults = testedHeaders, 
                                               novelResults = testedNovels, footerResults = testedFooters, nGramLength = args(3))
    
    // store results
    import java.io._
    val file = new File("nGram" + args(3) + ".txt")
    val bw = new BufferedWriter(new FileWriter(file))  
    bw.write(confusionMatrix)
    bw.close()
    
    val pConfusionMatrix = sc.parallelize(confusionMatrix)
    
    pConfusionMatrix.coalesce(args(2).toInt).saveAsTextFile(args(1))
    
    System.exit(0)
  }
  
  
  // # Setup Helper Functions =====================================
  
  //poor man's stop word removal of words with > 4 characters
  def wordCount(text: String): Array[(String,Int)] = {
  text
  .split("\\W+")
  .filter(_.size > 4)
  .map((_, 1))
  .groupBy(_._1).
  toArray
  .map(words => (words._1, words._2.size))
  }
  
  
  def wordCountLabled(fileAndText: Array[(String, String)]): Array[(String, Array[(String, Int)])]  = {
  fileAndText
  .map(t => (t._1, wordCount(t._2)))
  }
  
  
  // Cleanse file name --------------------------------------------
  def cleanseFileName(fileName: String): String = {
  fileName.toLowerCase
  .replace("hdfs://hc.gps.stthomas.edu:8020/seis736/gutenberg/", "")
  .replace(".txt","")
  }
  
  
  // Cleanse novel text -------------------------------------------
  // filter out tiny and giant books
  def acceptPageCountClassify(novel:String): Boolean = {  
  // assuming 300 words per page based on 
  // http://www.megcabot.com/about-meg-cabot/frequently-asked-questions-getting-published/
  // we only want books from 50-1000 pages long, this works out to be 17.5K to 300K words
  val wordCount = novel.split("\\W+").size
  
  wordCount >= 17500 & wordCount <= 300000
  }
  
  // Deal with simple formatting issues
  //white space and all punctuation besides apostrophe
  //remove 2+ spacing
  def formatNovel(novel: String): String = {
  novel.toLowerCase
  .replaceAll("\\s|[^\\P{Punct}']", " ")
.replaceAll(" +", " ")
  }


// Drop header
def dropHeader(novel: String): String = {
val splitOn: String = "(^.{0,40000})(?:\\*end the small print (for public domain)?.{0,10}\\*ver.{0,10}\\*end\\*|\\*\\*\\* start .{0,8}?project gutenberg .{1,70}\\*\\*\\*|online distributed proofreading team|distributed proofreaders|produced by|project gutenberg|ebook|etext|text file)"

//split and keep only the last chunk (the part after all of the matches)
novel.split(splitOn)(1)
}


// Drop footer
def dropFooter(novel: String): String = {
val splitOn: String = "(?:this file should be named .{0,10}\\.(txt|zip)|end.{1,10}project gutenberg)(.{0,20000})$"

//split and keep only the first chunk (the part before all of the matches)
novel.split(splitOn)(0)
}


// Completely cleanse the novel string
def getNovel(novel: String): String = { 
val noHead: String = dropHeader(novel)

dropFooter(noHead)
}


// Get header
def getHeader(novel: String): String = {
val header = "(^.{0,40000}\\*end the small print.{0,30}\\*ver.{0,10}\\*end\\*|^.{0,40000}\\*\\*\\* start .{0,8}?project gutenberg .{1,70}\\*\\*\\*|^.{0,40000}online distributed proofreading team|^.{0,40000}distributed proofreaders|^.{0,40000}produced by|project gutenberg|^.{0,40000}ebook|^.{0,40000}etext|^.{0,40000}text file)(.*)".r

//split and keep only the last chunk (the part after all of the matches)
val header(head, remainder) = novel

head
}


// Get footer
def getFooter(novel: String): String = {
val footer = "(this file should be named .{0,10}\\.txt.{0,25000}$|this file should be named .{0,10}\\.zip.{0,20000}$|end.{1,10}project gutenberg.{0,20000}$|project gutenberg.{0,20000}$|.{1}$)".r.unanchored

//split and keep only the last chunk (the part after all of the matches)
val footer(foot) = novel

foot
}

// Sentiment Analysis -------------------------------------------------
// filter out tiny and giant books
def acceptPageCountSentiment(novel:String): Boolean = {  
// assuming 300 words per page based on 
// http://www.megcabot.com/about-meg-cabot/frequently-asked-questions-getting-published/
// we only want books from 100-500 pages long, this works out to be 35K to 150K words
val wordCount = novel.split("\\W+").size

wordCount >= 35000 & wordCount <= 150000
}

// split novel into n chunks, where splitCount = n
def chunkNovel(novel: String): Array[String] ={
// split novel into words
val splitNovel = novel.split("\\W+")

// create empty output container
// splitting to avg page count per book in dataset
// to limit difference in words per chunk between books
val splitCount: Int = 245
val chunkedNovel = new Array[String](splitCount)

// setup some variables for moving through the novel's text
val pace = splitNovel.size / splitCount.toFloat
var pacer: Double = 0.0

// setup container for ith chunk
var nextChunk = new String

// split the novel into n-ish sized chunks
var i = 0
while (i < splitCount){
var subsetStart: Int = pacer.toInt

pacer = pacer + pace

var subsetEnd: Int = (pacer - (pacer % 1)).toInt

//If on last chunk, include the rest of the string
if (i < splitCount) {
nextChunk = splitNovel.slice(subsetStart, subsetEnd).mkString(" ")
} else {
nextChunk = splitNovel.slice(subsetStart, splitCount).mkString(" ")
}

chunkedNovel(i) = nextChunk

i += 1
}

// return the results
chunkedNovel
}

// setup training and test sets --------------------------------------

//poor man's stop word removal of words with > 4 characters
// drop final nGram that doesn't have enough words
def toWordNGram(text: String, nGramLength: Int): Array[Array[String]] = {
text.split("\\W+")
.filter(_.size > 4)
.grouped(nGramLength)
.toArray
.filter(x => x.length == nGramLength)
}


def toTestSet(fileNames: org.apache.spark.rdd.RDD[String],
texts: org.apache.spark.rdd.RDD[(String, String)], 
nGramLength: Int): org.apache.spark.rdd.RDD[Array[(String, Int)]] = {

fileNames
.map(x => (x,x))
.join(texts)
.map(x => x._2._2)
.flatMap(toWordNGram(_, nGramLength = nGramLength))
.map(_.mkString(" "))
.map(wordCount(_))
} 



def getWordPDF(word: String, dist: Array[(String, Double)]): Double = {
val distMatches = dist.filter(d => d._1 == word)

if (distMatches.length > 0) {
distMatches(0)._2
} else {
0.00000001
}
}

def getPMF(obs: Array[(String, Int)], dist: Array[(String, Double)]): Double = {
obs
.map(x => math.pow(getWordPDF(x._1, dist), x._2))
.product
}

// create naive Bayes classifyer with flat priors ------------------------------------
// returns 0 for header, 1 for novel, 2 for footer
def classify(obs: Array[(String, Int)], headerDist: Array[(String, Double)], 
novelDist: Array[(String, Double)], footerDist:Array[(String, Double)]): 
Int = {

// calculate the probability mass function of each class
val pmfHeader = getPMF(obs = obs, dist = headerDist)

val pmfNovel = getPMF(obs = obs, dist = novelDist)

val pmfFooter = getPMF(obs = obs, dist = footerDist)


// normalize the probabilities
val normalizingConstant = pmfHeader + pmfNovel + pmfFooter

val normHeader = pmfHeader / normalizingConstant

val normNovel = pmfNovel / normalizingConstant

val normFooter = pmfFooter / normalizingConstant


// returns 0 for header, 1 for novel, 2 for footer
if (normHeader > normNovel & normHeader > normFooter) {
0
} else if (normNovel > normHeader & normNovel > normFooter) {
1
} else {
2
}
}  


// summarize results in confusion matrix -------------------------------------------
def summaryToString(actual: Int, summary: (Int, Int, Int)): String = {
actual.toString + ": " + summary._1.toString + " " + summary._2.toString + " " + summary._3.toString + "\n" 
}

// add zeros for confusion matrix cells with no observations
def fillSummary(summary: Array[(Int, Int, Int)]): (Int, Int, Int) = {
// setup working environment
val actual: Int = summary(0)._1
var fullSummary: Array[(Int, Int, Int)] = summary

// add the missing observations
for (i <- 0 to 2) {
if (summary.filter(_._2 == i).length == 0) {
fullSummary = fullSummary.union(Array((actual,i,0))) 
}
}

// order the output
val count0: Int = fullSummary.filter(_._2 == 0)(0)._3
val count1: Int = fullSummary.filter(_._2 == 1)(0)._3
val count2: Int = fullSummary.filter(_._2 == 2)(0)._3

(count0, count1, count2)
}

def summarizeClassResults(actual: Int, predicted: Array[Int]): String = {
val rawSummary: Array[(Int, Int, Int)] = predicted   
.map((_, 1))
.groupBy(_._1)
.toArray
.map(x => (actual, x._1, x._2.size))

summaryToString(actual = actual, summary = fillSummary(rawSummary))
}


def getConfusionMatrix(headerResults: Array[Int], novelResults: Array[Int],
footerResults: Array[Int], nGramLength: String): String = {

// get summary string for each class
val headerSummary: String = summarizeClassResults(actual = 0, predicted = headerResults) 

val novelSummary: String = summarizeClassResults(actual = 1, predicted = novelResults)

val footerSummary: String = summarizeClassResults(actual = 2, predicted = footerResults)

// concatinate class summaries into confusion matrix
"n-gram length: " + nGramLength + 
"\n     0     2     3\n" + 
headerSummary + 
novelSummary + 
footerSummary
}
}



// val args = Array("/SEIS736/gutenberg/cr*", "sparkout", "1", "30")

/*
* TODO:
* stem words
* remove stop words
* create makeDist function
* create makeTrainSet function
* use laplace estimator in getWord
* use immutable data types for distribution tables
*/
