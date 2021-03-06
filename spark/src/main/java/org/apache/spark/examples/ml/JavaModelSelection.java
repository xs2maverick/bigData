package org.apache.spark.examples.ml;

import java.util.List;
import com.google.common.collect.Lists;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.ml.Model;
import org.apache.spark.ml.Pipeline;
import org.apache.spark.ml.PipelineStage;
import org.apache.spark.ml.classification.LogisticRegression;
import org.apache.spark.ml.evaluation.BinaryClassificationEvaluator;
import org.apache.spark.ml.feature.HashingTF;
import org.apache.spark.ml.feature.Tokenizer;
import org.apache.spark.ml.param.ParamMap;
import org.apache.spark.ml.tuning.CrossValidator;
import org.apache.spark.ml.tuning.CrossValidatorModel;
import org.apache.spark.ml.tuning.ParamGridBuilder;
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;

public class JavaModelSelection {
	public static void main(String[] args) {
		SparkConf conf = new SparkConf().setMaster("local[6]").setAppName("JavaCrossValidatorExample");
		JavaSparkContext jsc = new JavaSparkContext(conf);
		SQLContext jsql = new SQLContext(jsc);
		
		// Prepare training documents, which are labeled.
		List<LabeledDocument> localTraining = Lists.newArrayList(
		  new LabeledDocument(0L, "a b c d e spark", 1.0),
		  new LabeledDocument(1L, "b d", 0.0),
		  new LabeledDocument(2L, "spark f g h", 1.0),
		  new LabeledDocument(3L, "hadoop mapreduce", 0.0),
		  new LabeledDocument(4L, "b spark who", 1.0),
		  new LabeledDocument(5L, "g d a y", 0.0),
		  new LabeledDocument(6L, "spark fly", 1.0),
		  new LabeledDocument(7L, "was mapreduce", 0.0),
		  new LabeledDocument(8L, "e spark program", 1.0),
		  new LabeledDocument(9L, "a e c l", 0.0),
		  new LabeledDocument(10L, "spark compile", 1.0),
		  new LabeledDocument(11L, "hadoop software", 0.0));
		DataFrame training =
		    jsql.applySchema(jsc.parallelize(localTraining), LabeledDocument.class);
		
		
		// Configure an ML pipeline, which consists of three stages: tokenizer, hashingTF, and lr.
		Tokenizer tokenizer = new Tokenizer()
		  .setInputCol("text")
		  .setOutputCol("words");
		HashingTF hashingTF = new HashingTF()
		  .setNumFeatures(1000)
		  .setInputCol(tokenizer.getOutputCol())
		  .setOutputCol("features");
		LogisticRegression lr = new LogisticRegression()
		  .setMaxIter(10)
		  .setRegParam(0.01);
		Pipeline pipeline = new Pipeline()
		  .setStages(new PipelineStage[] {tokenizer, hashingTF, lr});

		// We now treat the Pipeline as an Estimator, wrapping it in a CrossValidator instance.
		// This will allow us to jointly choose parameters for all Pipeline stages.
		// A CrossValidator requires an Estimator, a set of Estimator ParamMaps, and an Evaluator.
		CrossValidator crossval = new CrossValidator()
		    .setEstimator(pipeline)
		    .setEvaluator(new BinaryClassificationEvaluator());
		// We use a ParamGridBuilder to construct a grid of parameters to search over.
		// With 3 values for hashingTF.numFeatures and 2 values for lr.regParam,
		// this grid will have 3 x 2 = 6 parameter settings for CrossValidator to choose from.
		//1. 减少feature个数（人工定义留多少个feature、算法选取这些feature）
		//2. 规格化（留下所有的feature，但对于部分feature定义其parameter非常小）
		ParamMap[] paramGrid = new ParamGridBuilder()
		    .addGrid(hashingTF.numFeatures(), new int[]{80, 100, 85})
		    .addGrid(lr.regParam(), new double[]{0.1,0.01, 0.001})
		    .build();
		crossval.setEstimatorParamMaps(paramGrid);
		crossval.setNumFolds(3); // Use 3+ in practice

		// Run cross-validation, and choose the best set of parameters.
		CrossValidatorModel cvModel = crossval.fit(training);
		// Get the best LogisticRegression model (with the best set of parameters from paramGrid).
		Model lrModel = cvModel.bestModel();
		System.out.println("best param fittingParamMap: "+ lrModel.fittingParamMap());
		System.out.println("best param : "+ cvModel.explainParams());
		System.out.println("best param : "+ cvModel.fittingParamMap());
		
		// Prepare test documents, which are unlabeled.
		List<Document> localTest = Lists.newArrayList(
		  new Document(4L, "spark i j k"),
		  new Document(5L, "l m n"),
		  new Document(6L, "mapreduce spark"),
		  new Document(7L, "apache hadoop"));
		DataFrame test = jsql.applySchema(jsc.parallelize(localTest), Document.class);

		// Make predictions on test documents. cvModel uses the best model found (lrModel).
		cvModel.transform(test).registerAsTable("prediction");
		DataFrame predictions = jsql.sql("SELECT id, text, score, prediction FROM prediction");
		for (Row r: predictions.collect()) {
		  System.out.println("(" + r.get(0) + ", " + r.get(1) + ") --> score=" + r.get(2)
		      + ", prediction=" + r.get(3));
		}
	}
}
