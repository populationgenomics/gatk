package org.broadinstitute.hellbender.tools.walkers.haplotypecaller;

import com.google.common.collect.ImmutableMap;
import htsjdk.samtools.SamFiles;
import htsjdk.samtools.util.FileExtensions;
import htsjdk.samtools.util.Log;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.GenotypeBuilder;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFConstants;
import htsjdk.variant.vcf.VCFHeader;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.broadinstitute.hellbender.CommandLineProgramTest;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.argumentcollections.IntervalArgumentCollection;
import org.broadinstitute.hellbender.engine.FeatureDataSource;
import org.broadinstitute.hellbender.engine.ReadsDataSource;
import org.broadinstitute.hellbender.engine.ReadsPathDataSource;
import org.broadinstitute.hellbender.engine.spark.AssemblyRegionArgumentCollection;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.testutils.ArgumentsBuilder;
import org.broadinstitute.hellbender.testutils.IntegrationTestSpec;
import org.broadinstitute.hellbender.testutils.SamAssertionUtils;
import org.broadinstitute.hellbender.testutils.VariantContextTestUtils;
import org.broadinstitute.hellbender.tools.walkers.genotyper.AlleleSubsettingUtils;
import org.broadinstitute.hellbender.tools.walkers.genotyper.GenotypeCalculationArgumentCollection;
import org.broadinstitute.hellbender.utils.BaseUtils;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.gcs.BucketUtils;
import org.broadinstitute.hellbender.utils.io.IOUtils;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;
import org.broadinstitute.hellbender.utils.variant.HomoSapiensConstants;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Test(groups = {"variantcalling"})
public class HaplotypeCallerIntegrationTest extends CommandLineProgramTest {

    // If true, update the expected outputs in tests that assert an exact match vs. prior output,
    // instead of actually running the tests. Can be used with "./gradlew test -Dtest.single=HaplotypeCallerIntegrationTest"
    // to update all of the exact-match tests at once. After you do this, you should look at the
    // diffs in the new expected outputs in git to confirm that they are consistent with expectations.
    public static final boolean UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS = false;

    public static final String TEST_FILES_DIR = toolsTestDir + "haplotypecaller/";

    /*
     * Make sure that someone didn't leave the UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS toggle turned on
     */
    @Test
    public void assertThatExpectedOutputUpdateToggleIsDisabled() {
        Assert.assertFalse(UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS, "The toggle to update expected outputs should not be left enabled");
    }

    @DataProvider(name="HaplotypeCallerTestInputs")
    public Object[][] getHaplotypCallerTestInputs() {
        return new Object[][] {
                {NA12878_20_21_WGS_bam, b37_reference_20_21},
                {NA12878_20_21_WGS_cram, b37_reference_20_21}
        };
    }
    /*
     * Test that in VCF mode we're consistent with past GATK4 results
     */
    @Test(dataProvider="HaplotypeCallerTestInputs")
    public void testVCFModeIsConsistentWithPastResults(final String inputFileName, final String referenceFileName) throws Exception {
        Utils.resetRandomGenerator();

        final File output = createTempFile("testVCFModeIsConsistentWithPastResults", ".vcf");
        final File expected = new File(TEST_FILES_DIR, "expected.testVCFMode.gatk4.vcf");

        final String outputPath = UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS ? expected.getAbsolutePath() : output.getAbsolutePath();

        final String[] args = {
                "-I", inputFileName,
                "-R", referenceFileName,
                "-L", "20:10000000-10100000",
                "-O", outputPath,
                "-pairHMM", "AVX_LOGLESS_CACHING",
                "--" + StandardArgumentDefinitions.ADD_OUTPUT_VCF_COMMANDLINE, "false"
        };

        runCommandLine(args);

        // Test for an exact match against past results
        if ( ! UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS ) {
            IntegrationTestSpec.assertEqualTextFiles(output, expected);
        }
    }
    
    /*
     * Test that in JunctionTree mode we're consistent with past JunctionTree results (over non-complicated data)
     */
    @Test(dataProvider="HaplotypeCallerTestInputs")
    public void testLinkedDebruijnModeIsConsistentWithPastResults(final String inputFileName, final String referenceFileName) throws Exception {
        Utils.resetRandomGenerator();

        final File output = createTempFile("testLinkedDebruijnModeIsConsistentWithPastResults", ".vcf");
        final File expected = new File(TEST_FILES_DIR, "expected.testLinkedDebruijnMode.gatk4.vcf");

        final String outputPath = UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS ? expected.getAbsolutePath() : output.getAbsolutePath();

        final String[] args = {
                "-I", inputFileName,
                "-R", referenceFileName,
                "-L", "20:10000000-10100000",
                "-O", outputPath,
                "-pairHMM", "AVX_LOGLESS_CACHING",
                "--"+ReadThreadingAssemblerArgumentCollection.LINKED_DE_BRUIJN_GRAPH_LONG_NAME,
                "--" + StandardArgumentDefinitions.ADD_OUTPUT_VCF_COMMANDLINE, "false"
        };

        runCommandLine(args);

        // Test for an exact match against past results
        if ( ! UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS ) {
            IntegrationTestSpec.assertEqualTextFiles(output, expected);
        }
    }

    /**
     * Test that exposure and modification of Smith-Waterman parameters that were previously constants is consistent with past results (over non-complicated data).
     * Expected outputs were generated by changing previously hardcoded constants in the previous commit to the exposed values below.
     * See https://github.com/broadinstitute/gatk/pull/6885.
     */
    @Test(dataProvider="HaplotypeCallerTestInputs")
    public void testExposureOfSmithWatermanParametersIsConsistentWithPastResults(final String inputFileName, final String referenceFileName) throws Exception {
        Utils.resetRandomGenerator();

        final File output = createTempFile("testExposureOfSmithWatermanParametersIsConsistentWithPastResults", ".vcf");
        final File expected = new File(TEST_FILES_DIR, "expected.testExposureOfSmithWatermanParameters.HC.gatk4.vcf");

        final String outputPath = UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS ? expected.getAbsolutePath() : output.getAbsolutePath();

        final String[] args = {
                "-I", inputFileName,
                "-R", referenceFileName,
                "-L", "20:10000000-10100000",
                "-O", outputPath,
                "-pairHMM", "AVX_LOGLESS_CACHING",
                "--" + AssemblyBasedCallerArgumentCollection.SMITH_WATERMAN_DANGLING_END_MATCH_VALUE_LONG_NAME, "1",
                "--" + AssemblyBasedCallerArgumentCollection.SMITH_WATERMAN_DANGLING_END_MISMATCH_PENALTY_LONG_NAME, "-2",
                "--" + AssemblyBasedCallerArgumentCollection.SMITH_WATERMAN_DANGLING_END_GAP_OPEN_PENALTY_LONG_NAME, "-3",
                "--" + AssemblyBasedCallerArgumentCollection.SMITH_WATERMAN_DANGLING_END_GAP_EXTEND_PENALTY_LONG_NAME, "-4",
                "--" + AssemblyBasedCallerArgumentCollection.SMITH_WATERMAN_HAPLOTYPE_TO_REFERENCE_MATCH_VALUE_LONG_NAME, "5",
                "--" + AssemblyBasedCallerArgumentCollection.SMITH_WATERMAN_HAPLOTYPE_TO_REFERENCE_MISMATCH_PENALTY_LONG_NAME, "-6",
                "--" + AssemblyBasedCallerArgumentCollection.SMITH_WATERMAN_HAPLOTYPE_TO_REFERENCE_GAP_OPEN_PENALTY_LONG_NAME, "-7",
                "--" + AssemblyBasedCallerArgumentCollection.SMITH_WATERMAN_HAPLOTYPE_TO_REFERENCE_GAP_EXTEND_PENALTY_LONG_NAME, "-8",
                "--" + AssemblyBasedCallerArgumentCollection.SMITH_WATERMAN_READ_TO_HAPLOTYPE_MATCH_VALUE_LONG_NAME, "9",
                "--" + AssemblyBasedCallerArgumentCollection.SMITH_WATERMAN_READ_TO_HAPLOTYPE_MISMATCH_PENALTY_LONG_NAME, "-10",
                "--" + AssemblyBasedCallerArgumentCollection.SMITH_WATERMAN_READ_TO_HAPLOTYPE_GAP_OPEN_PENALTY_LONG_NAME, "-11",
                "--" + AssemblyBasedCallerArgumentCollection.SMITH_WATERMAN_READ_TO_HAPLOTYPE_GAP_EXTEND_PENALTY_LONG_NAME, "-12",
                "--" + StandardArgumentDefinitions.ADD_OUTPUT_VCF_COMMANDLINE, "false"
        };

        runCommandLine(args);

        // Test for an exact match against past results
        if ( ! UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS ) {
            IntegrationTestSpec.assertEqualTextFiles(output, expected);
        }
    }

    /*
     * Test that in VCF mode we're consistent with past GATK4 results
     *
     * Test currently throws an exception due to lack of support for allele-specific annotations in VCF mode
     */
    @Test(dataProvider="HaplotypeCallerTestInputs", expectedExceptions = UserException.class)
    public void testVCFModeIsConsistentWithPastResults_AlleleSpecificAnnotations(final String inputFileName, final String referenceFileName) throws Exception {
        Utils.resetRandomGenerator();

        //NOTE: AlleleSpecific support in the VCF mode is implemented but bogus for now.
        // This test should not be treated as a strict check of correctness.
        final File output = createTempFile("testVCFModeIsConsistentWithPastResults", ".vcf");
        final File expected = new File(TEST_FILES_DIR + "expected.testVCFMode.gatk4.alleleSpecific.vcf");

        final String outputPath = UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS ? expected.getAbsolutePath() : output.getAbsolutePath();

        final String[] args = {
                "-I", inputFileName,
                "-R", referenceFileName,
                "-L", "20:10000000-10100000",
                "-O", outputPath,
                "-G", "StandardAnnotation",
                "-G", "StandardHCAnnotation",
                "-G", "AS_StandardAnnotation",
                "-pairHMM", "AVX_LOGLESS_CACHING",
                "--" + StandardArgumentDefinitions.ADD_OUTPUT_VCF_COMMANDLINE, "false"
        };

        runCommandLine(args);

        // Test for an exact match against past results
        if ( ! UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS ) {
            IntegrationTestSpec.assertEqualTextFiles(output, expected);
        }
    }

    /*
     * Test that in VCF mode we're >= 99% concordant with GATK3.8 results
     */
    @Test(dataProvider="HaplotypeCallerTestInputs")
    public void testVCFModeIsConcordantWithGATK3_8Results(final String inputFileName, final String referenceFileName) throws Exception {
        Utils.resetRandomGenerator();

        final File output = createTempFile("testVCFModeIsConcordantWithGATK3Results", ".vcf");
        //Created by running:
        // java -jar gatk.3.8-4-g7b0250253f.jar -T HaplotypeCaller \
        // -I ./src/test/resources/large/CEUTrio.HiSeq.WGS.b37.NA12878.20.21.bam \
        // -R src/test/resources/large/human_g1k_v37.20.21.fasta -L 20:10000000-10100000 \
        // --out expected.testVCFMode.gatk3.8-4-g7b0250253.vcf -G StandardHC -G Standard \
        // --disableDithering --no_cmdline_in_header  -dt NONE --maxReadsInRegionPerSample 100000000 --minReadsPerAlignmentStart 100000 \
        // -pairHMM VECTOR_LOGLESS_CACHING
        final File gatk3Output = new File(TEST_FILES_DIR + "expected.testVCFMode.gatk3.8-4-g7b0250253.vcf");

        final String[] args = {
                "-I", inputFileName,
                "-R", referenceFileName,
                "-L", "20:10000000-10100000",
                "-O", output.getAbsolutePath(),
                "-pairHMM", "AVX_LOGLESS_CACHING",
                "--" + AssemblyBasedCallerArgumentCollection.ALLELE_EXTENSION_LONG_NAME, "2",
        };

        runCommandLine(args);

        final double concordance = calculateConcordance(output, gatk3Output);
        Assert.assertTrue(concordance >= 0.99, "Concordance with GATK 3.8 in VCF mode is < 99% (" +  concordance + ")");
    }

    /*
     * Test that the this version of DRAGEN-GATK has not changed relative to the last version with the recommended arguments enabled
     */
    @Test(dataProvider="HaplotypeCallerTestInputs")
    public void testDRAGENDefaultArgIsConsistentWithPastResults(final String inputFileName, final String referenceFileName) throws Exception {
        Utils.resetRandomGenerator();

        final File output = createTempFile("testDragenSimpleModeIsConsistentWithPastResults", ".vcf");
        final File expected = new File(TEST_FILES_DIR + "expected.testVCFMode.gatk4.DRAGEN.vcf");
        final String outputPath = UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS ? expected.getAbsolutePath() : output.getAbsolutePath();

        final String[] args = {
                "-I", inputFileName,
                "-R", referenceFileName,
                "-L", "20:10000000-10100000",
                "-O", outputPath,
                "-pairHMM", "AVX_LOGLESS_CACHING",
                "--dragen-mode",
                // STRE arguments
                "--dragstr-params-path", TEST_FILES_DIR+"example.dragstr-params.txt",
                "--" + StandardArgumentDefinitions.ADD_OUTPUT_VCF_COMMANDLINE, "false",
        };

        runCommandLine(args);
        if ( ! UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS ) {
            IntegrationTestSpec.assertEqualTextFiles(output, expected);
        }
    }

    /*
     * Test that the this version of DRAGEN-GATK has not changed relative to the last version with the recommended arguments enabled
     */
    @Test(dataProvider="HaplotypeCallerTestInputs")
    public void testDRAGENGATKModeIsConsistentWithPastResults(final String inputFileName, final String referenceFileName) throws Exception {
        Utils.resetRandomGenerator();

        final File output = createTempFile("testDRAGENGATKModeIsConsistentWithPastResults", ".vcf");
        final File expected = new File(TEST_FILES_DIR + "expected.testVCFMode.gatk4.DRAGEN.vcf");
        final String outputPath = UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS ? expected.getAbsolutePath() : output.getAbsolutePath();

        final String[] args = {
                "-I", inputFileName,
                "-R", referenceFileName,
                "-L", "20:10000000-10100000",
                "-O", outputPath,
                "-pairHMM", "AVX_LOGLESS_CACHING",
                // FRD arguments
                "--apply-frd", "--transform-dragen-mapping-quality", "--mapping-quality-threshold-for-genotyping", "1", "--disable-cap-base-qualities-to-map-quality", "--minimum-mapping-quality", "1",
                // BQD arguments
                "--apply-bqd",  "--soft-clip-low-quality-ends",
                // Dynamic read disqualification arguments"
                "--enable-dynamic-read-disqualification-for-genotyping", "--expected-mismatch-rate-for-read-disqualification", "0.03",
                // Genotyper arguments
                "--genotype-assignment-method", "USE_POSTERIOR_PROBABILITIES",  "--standard-min-confidence-threshold-for-calling", "3", "--use-posteriors-to-calculate-qual",
                // STRE arguments
                "--dragstr-params-path", TEST_FILES_DIR+"example.dragstr-params.txt",
                // misc arguments
                "--enable-legacy-graph-cycle-detection", "--padding-around-indels", "150",
                "--" + AssemblyBasedCallerArgumentCollection.ALLELE_EXTENSION_LONG_NAME, "1",
                "--" + StandardArgumentDefinitions.ADD_OUTPUT_VCF_COMMANDLINE, "false",
        };

        runCommandLine(args);
        if ( ! UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS ) {
            IntegrationTestSpec.assertEqualTextFiles(output, expected);
        }
    }


    /*
     * Test that in VCF mode we're >= 99% concordant with GATK3.8 results
     *
     * Test currently throws an exception due to lack of support for allele-specific annotations in VCF mode
     */
    @Test(dataProvider="HaplotypeCallerTestInputs", expectedExceptions = UserException.class)
    public void testVCFModeIsConcordantWithGATK3_8ResultsAlleleSpecificAnnotations(final String inputFileName, final String referenceFileName) throws Exception {
        Utils.resetRandomGenerator();

        final File output = createTempFile("testVCFModeIsConcordantWithGATK3.8ResultsAlleleSpecificAnnotations", ".vcf");

        //Created by running
        //java -jar gatk.3.8-4-g7b0250253f.jar  -T HaplotypeCaller \
        // -I ./src/test/resources/large/CEUTrio.HiSeq.WGS.b37.NA12878.20.21.bam \
        // -R src/test/resources/large/human_g1k_v37.20.21.fasta -L 20:10000000-10100000 \
        // --out expected.testVCFMode.gatk3.8-4-g7b0250253f.alleleSpecific.vcf -G StandardHC -G Standard -G AS_Standard \
        // --disableDithering --no_cmdline_in_header  -dt NONE --maxReadsInRegionPerSample 100000000 --minReadsPerAlignmentStart 100000 \
        // -pairHMM VECTOR_LOGLESS_CACHING
        final File gatk3Output = new File(TEST_FILES_DIR + "expected.testVCFMode.gatk3.8-4-g7b0250253f.alleleSpecific.vcf");

        final String[] args = {
                "-I", inputFileName,
                "-R", referenceFileName,
                "-L", "20:10000000-10100000",
                "-O", output.getAbsolutePath(),
                "-G", "StandardAnnotation",
                "-G", "StandardHCAnnotation",
                "-G", "AS_StandardAnnotation",
                "-pairHMM", "AVX_LOGLESS_CACHING",
        };

        runCommandLine(args);

        final double concordance = calculateConcordance(output, gatk3Output);
        Assert.assertTrue(concordance >= 0.99, "Concordance with GATK 3.8 in AS VCF mode is < 99% (" +  concordance + ")");
    }

    /*
     * Test that in GVCF mode we're consistent with past GATK4 results
     */
    @Test(dataProvider="HaplotypeCallerTestInputs")
    public void testGVCFModeIsConsistentWithPastResults(final String inputFileName, final String referenceFileName) throws Exception {
        Utils.resetRandomGenerator();

        final File output = createTempFile("testGVCFModeIsConsistentWithPastResults", ".g.vcf");
        final File expected = new File(TEST_FILES_DIR, "expected.testGVCFMode.gatk4.g.vcf");

        final String outputPath = UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS ? expected.getAbsolutePath() : output.getAbsolutePath();

        final String[] args = {
                "-I", inputFileName,
                "-R", referenceFileName,
                "-L", "20:10000000-10100000",
                "-O", outputPath,
                "--" + AssemblyBasedCallerArgumentCollection.EMIT_REF_CONFIDENCE_LONG_NAME, ReferenceConfidenceMode.GVCF.toString(),
                "-pairHMM", "AVX_LOGLESS_CACHING",
                "--" + StandardArgumentDefinitions.ADD_OUTPUT_VCF_COMMANDLINE, "false"
        };

        runCommandLine(args);

        // Test for an exact match against past results
        if ( ! UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS ) {
            IntegrationTestSpec.assertEqualTextFiles(output, expected);
        }
    }


    /*
     * Minimal test that the non-seq graph haplotype detection code is equivalent using either seq graphs or kmer graphs
     *
     *  NOTE: --disable-sequence-graph-simplification is currently an experimental feature that does not directly match with
     *        the regular HaplotypeCaller. Specifically the haplotype finding code does not perform correctly at complicated
     *        sites, which is illustrated by this test. Use this mode at your own risk.
     */
    @Test(dataProvider="HaplotypeCallerTestInputs", enabled = false)
    public void testGVCFModeIsConsistentWithPastResultsUsingKmerGraphs(final String inputFileName, final String referenceFileName) throws Exception {
        Utils.resetRandomGenerator();

        final File output = createTempFile("testGVCFModeIsConsistentWithPastResults", ".g.vcf");
        final File expected = new File(TEST_FILES_DIR, "expected.testGVCFMode.gatk4.g.vcf");

        final String outputPath = UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS ? expected.getAbsolutePath() : output.getAbsolutePath();

        final String[] args = {
                "-I", inputFileName,
                "-R", referenceFileName,
                "-L", "20:10000000-10100000",
                "-O", outputPath,
                "-ERC", "GVCF",
                "-pairHMM", "AVX_LOGLESS_CACHING",
                "--disable-sequence-graph-simplification",
                "--" + AssemblyBasedCallerArgumentCollection.ALLELE_EXTENSION_LONG_NAME, "2",
                "--" + StandardArgumentDefinitions.ADD_OUTPUT_VCF_COMMANDLINE, "false"
        };

        runCommandLine(args);

        // Test for an exact match against past results
        if ( ! UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS ) {
            IntegrationTestSpec.assertEqualTextFiles(output, expected);
        }
    }

    /*
     * Test that in GVCF mode we're consistent with past GATK4 results using AS_ annotations
     *
     * Updated on 09/01/17 to account for changes to AS_RankSum annotations the annotations were checked against GATK3
     */
    @Test(dataProvider="HaplotypeCallerTestInputs")
    public void testGVCFModeIsConsistentWithPastResults_AlleleSpecificAnnotations(final String inputFileName, final String referenceFileName) throws Exception {
        Utils.resetRandomGenerator();

        final File output = createTempFile("testGVCFModeIsConsistentWithPastResults_AlleleSpecificAnnotations", ".g.vcf");
        final File expected = new File(TEST_FILES_DIR + "expected.testGVCFMode.gatk4.alleleSpecific.g.vcf");

        final String outputPath = UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS ? expected.getAbsolutePath() : output.getAbsolutePath();

        final String[] args = {
                "-I", inputFileName,
                "-R", referenceFileName,
                "-L", "20:10000000-10100000",
                "-O", outputPath,
                "-G", "StandardAnnotation",
                "-G", "StandardHCAnnotation",
                "-G", "AS_StandardAnnotation",
                "--" + AssemblyBasedCallerArgumentCollection.EMIT_REF_CONFIDENCE_LONG_NAME, ReferenceConfidenceMode.GVCF.toString(),
                "-pairHMM", "AVX_LOGLESS_CACHING",
                "--" + AssemblyBasedCallerArgumentCollection.ALLELE_EXTENSION_LONG_NAME, "2",
                "--" + StandardArgumentDefinitions.ADD_OUTPUT_VCF_COMMANDLINE, "false"
        };

        runCommandLine(args);

        // Test for an exact match against past results
        if ( ! UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS ) {
            IntegrationTestSpec.assertEqualTextFiles(output, expected);
        }
    }

    /*
     * Test that in GVCF mode we're >= 99% concordant with GATK3 results
     */
    @Test(dataProvider="HaplotypeCallerTestInputs", enabled=false) //disabled after reference confidence change in #5172
    public void testGVCFModeIsConcordantWithGATK3_8Results(final String inputFileName, final String referenceFileName) throws Exception {
        Utils.resetRandomGenerator();

        final File output = createTempFile("testGVCFModeIsConcordantWithGATK3Results", ".g.vcf");
        //Created by running:
        //java -jar  gatk.3.8-4-g7b0250253f.jar -T HaplotypeCaller \
        // -I ./src/test/resources/large/CEUTrio.HiSeq.WGS.b37.NA12878.20.21.bam \
        // -R src/test/resources/large/human_g1k_v37.20.21.fasta -L 20:10000000-10100000 \
        // -ERC GVCF --out expected.testGVCFMode.3.8-4-g7b0250253f.g.vcf -G StandardHC -G Standard \
        // --disableDithering --no_cmdline_in_header  -dt NONE --maxReadsInRegionPerSample 100000000 --minReadsPerAlignmentStart 100000 \
        // -pairHMM VECTOR_LOGLESS_CACHING
        final File gatk3Output = new File(TEST_FILES_DIR + "expected.testGVCFMode.3.8-4-g7b0250253f.g.vcf");

        final String[] args = {
                "-I", inputFileName,
                "-R", referenceFileName,
                "-L", "20:10000000-10100000",
                "-O", output.getAbsolutePath(),
                "--" + AssemblyBasedCallerArgumentCollection.EMIT_REF_CONFIDENCE_LONG_NAME, ReferenceConfidenceMode.GVCF.toString(),
                "--" + AssemblyBasedCallerArgumentCollection.ALLELE_EXTENSION_LONG_NAME, "2",
                "-pairHMM", "AVX_LOGLESS_CACHING",
        };

        runCommandLine(args);

        final double concordance = calculateConcordance(output, gatk3Output);
        Assert.assertTrue(concordance >= 0.99, "Concordance with GATK 3.8 in GVCF mode is < 99% (" +  concordance + ")");
    }

    /*
     * Test that the this version of DRAGEN-GATK has not changed relative to the last version with the recommended arguments enabled
     */
    @Test
    public void testFRDBQDDRAGENGATKOnDRAGENProcessedFile() throws Exception {
        Utils.resetRandomGenerator();
        final String inputFileName = largeFileTestDir + "DRAGENExampleBamSites.bam";
        final String intervals = TEST_FILES_DIR + "DRAGENTestSites.bed";

        final File output = createTempFile("testFRDBQDDRAGENGATKOnDRAGENProcessedFile", ".vcf");
        final File expected = new File(TEST_FILES_DIR + "expected.testVCFMode.gatk4.FRDBQD.vcf");
        final String outputPath = UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS ? expected.getAbsolutePath() : output.getAbsolutePath();

        final String[] args = {
                "-I", inputFileName,
                "-R", b37Reference,
                "-L", intervals,
                "-O", outputPath,
                "-pairHMM", "AVX_LOGLESS_CACHING",
                // FRD arguments
                "--apply-frd", "--transform-dragen-mapping-quality", "--mapping-quality-threshold-for-genotyping", "1", "--disable-cap-base-qualities-to-map-quality", "--minimum-mapping-quality", "1",
                // BQD arguments
                "--apply-bqd",  "--soft-clip-low-quality-ends",
                // Dynamic read disqualification arguments"
                "--enable-dynamic-read-disqualification-for-genotyping", "--expected-mismatch-rate-for-read-disqualification", "0.03",
                // misc arguments
                "--enable-legacy-graph-cycle-detection", "--padding-around-indels", "150",
                "--" + AssemblyBasedCallerArgumentCollection.ALLELE_EXTENSION_LONG_NAME, "1",
                "--" + StandardArgumentDefinitions.ADD_OUTPUT_VCF_COMMANDLINE, "false",
        };

        runCommandLine(args);
        if ( ! UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS ) {
            IntegrationTestSpec.assertEqualTextFiles(output, expected);
        }
    }


    @Test(dataProvider="HaplotypeCallerTestInputs", enabled=false) //disabled after reference confidence change in #5172
    public void testGVCFModeIsConcordantWithGATK3_8AlelleSpecificResults(final String inputFileName, final String referenceFileName) throws Exception {
        Utils.resetRandomGenerator();
        final File output = createTempFile("testGVCFModeIsConcordantWithGATK3_8AlelleSpecificResults", ".g.vcf");

        //Created by running:
        // java -jar gatk.3.8-4-g7b0250253f.jar -T HaplotypeCaller \
        // -I ./src/test/resources/large/CEUTrio.HiSeq.WGS.b37.NA12878.20.21.bam \
        // -R src/test/resources/large/human_g1k_v37.20.21.fasta -L 20:10000000-10100000 \
        // -ERC GVCF --out expected.testGVCFMode.gatk3.8-4-g7b0250253f.alleleSpecific.g.vcf -G StandardHC -G Standard -G AS_Standard \
        // --disableDithering --no_cmdline_in_header  -dt NONE --maxReadsInRegionPerSample 100000000 --minReadsPerAlignmentStart 100000 \
        // -pairHMM VECTOR_LOGLESS_CACHING
        final File gatk3Output = new File(TEST_FILES_DIR + "expected.testGVCFMode.gatk3.8-4-g7b0250253f.alleleSpecific.g.vcf");

        final String[] args = {
                "-I", inputFileName,
                "-R", referenceFileName,
                "-L", "20:10000000-10100000",
                "-O", output.getAbsolutePath(),
                "-G", "StandardAnnotation",
                "-G", "StandardHCAnnotation",
                "-G", "AS_StandardAnnotation",
                "--" + AssemblyBasedCallerArgumentCollection.EMIT_REF_CONFIDENCE_LONG_NAME, ReferenceConfidenceMode.GVCF.toString(),
                "--" + AssemblyBasedCallerArgumentCollection.ALLELE_EXTENSION_LONG_NAME, "2",
                "-pairHMM", "AVX_LOGLESS_CACHING",
        };

        runCommandLine(args);

        final double concordance = calculateConcordance(output, gatk3Output);
        Assert.assertTrue(concordance >= 0.99, "Concordance with GATK 3.8 in AS GVCF mode is < 99% (" +  concordance + ")");
    }

    @Test
    public void testGVCFModeGenotypePosteriors() throws Exception {
        Utils.resetRandomGenerator();

        final String inputFileName = NA12878_20_21_WGS_bam;
        final String referenceFileName =b37_reference_20_21;

        final File output = createTempFile("testGVCFModeIsConsistentWithPastResults", ".g.vcf");

        final String[] args = {
                "-I", inputFileName,
                "-R", referenceFileName,
                "-L", "20:10000000-10100000",
                "-O", output.getAbsolutePath(),
                "--" + AssemblyBasedCallerArgumentCollection.EMIT_REF_CONFIDENCE_LONG_NAME, ReferenceConfidenceMode.GVCF.toString(),
                "--" + GenotypeCalculationArgumentCollection.SUPPORTING_CALLSET_LONG_NAME,
                    largeFileTestDir + "1000G.phase3.broad.withGenotypes.chr20.10100000.vcf",
                "--" + GenotypeCalculationArgumentCollection.NUM_REF_SAMPLES_LONG_NAME, "2500",
                "-pairHMM", "AVX_LOGLESS_CACHING",
                "--" + AssemblyBasedCallerArgumentCollection.ALLELE_EXTENSION_LONG_NAME, "2",
                "--" + StandardArgumentDefinitions.ADD_OUTPUT_VCF_COMMANDLINE, "false"
        };

        runCommandLine(args);

        Pair<VCFHeader, List<VariantContext>> results = VariantContextTestUtils.readEntireVCFIntoMemory(output.getAbsolutePath());

        for (final VariantContext vc : results.getRight()) {
            final Genotype g = vc.getGenotype(0);
            if (g.hasDP() && g.getDP() > 0 && g.hasGQ() && g.getGQ() > 0) {
                Assert.assertTrue(g.hasExtendedAttribute(GATKVCFConstants.PHRED_SCALED_POSTERIORS_KEY));
            }
            if (isGVCFReferenceBlock(vc) ) {
                Assert.assertTrue(!vc.hasAttribute(GATKVCFConstants.GENOTYPE_PRIOR_KEY));
            }
            else if (!vc.getAlternateAllele(0).equals(Allele.NON_REF_ALLELE)){      //there are some variants that don't have non-symbolic alts
                Assert.assertTrue(vc.hasAttribute(GATKVCFConstants.GENOTYPE_PRIOR_KEY));
            }
        }
    }
    
    /*
     * Test that GQs are correct when the --floor-blocks argument is supplied
     */
    @Test(dataProvider="HaplotypeCallerTestInputs")
    public void testFloorGVCFBlocks(final String inputFileName, final String referenceFileName) throws Exception {
        Utils.resetRandomGenerator();

        final File output = createTempFile("testFloorGVCFBlocks", ".vcf");
        
        final List<String> requestedGqBands = Arrays.asList("10","20","30","40","50","60");
        
        final ArgumentsBuilder args = new ArgumentsBuilder().addInput(new File(inputFileName))
        .addReference(new File(referenceFileName))
        .addInterval(new SimpleInterval("20:10009880-10012631"))
        .add(StandardArgumentDefinitions.ADD_OUTPUT_VCF_COMMANDLINE, false)
        .add(AssemblyBasedCallerArgumentCollection.ALLELE_EXTENSION_LONG_NAME, "2")

        .add("pairHMM", "AVX_LOGLESS_CACHING")
        .addFlag("floor-blocks")
        .add("ERC", "GVCF")
        .addOutput(output);
        requestedGqBands.forEach(x -> args.add("GQB",x));
        runCommandLine(args);
        
        final List<String> allGqBands = new ArrayList<>(requestedGqBands);
        allGqBands.add("99");
        allGqBands.add("0");
        
        //The interval here is big, so use a FeatureDataSource to limit memory usage
        try (final FeatureDataSource<VariantContext> actualVcs = new FeatureDataSource<>(output)) {
            actualVcs.forEach(vc -> {
                //sometimes there are calls with alt alleles that are genotyped hom ref and those don't get floored
                if (vc.hasAttribute("END") && vc.getGenotype(0).hasGQ()) {
                    Assert.assertTrue(allGqBands.contains(Integer.toString(vc.getGenotype(0).getGQ())));
                }
            });
        }
    }

    // force calling bam (File) vcf (File) and intervals (String)
    @DataProvider(name="ForceCallingInputs")
    public Object[][] getForceCallingInputs() {
        return new Object[][] {
                {NA12878_20_21_WGS_bam, new File(TEST_FILES_DIR, "testGenotypeGivenAllelesMode_givenAlleles.vcf"), "20:10000000-10010000"},
                {NA12878_20_21_WGS_bam, new File(toolsTestDir, "mutect/gga_mode.vcf"), "20:9998500-10010000"}
        };
    }

    @Test(dataProvider = "ForceCallingInputs")
    public void testForceCalling(final String bamPath, final File forceCallingVcf, final String intervalString) throws IOException {
        Utils.resetRandomGenerator();
        final File output = createTempFile("testGenotypeGivenAllelesMode", ".vcf");

        final String[] args = {
                "-I", bamPath,
                "-R", b37Reference,
                "-L", intervalString,
                "-O", output.getAbsolutePath(),
                "-pairHMM", "AVX_LOGLESS_CACHING",
                "--" + StandardArgumentDefinitions.ADD_OUTPUT_VCF_COMMANDLINE, "false",
                "--" + AssemblyBasedCallerArgumentCollection.FORCE_CALL_ALLELES_LONG_NAME, forceCallingVcf.getAbsolutePath(),
                "--" + AssemblyBasedCallerArgumentCollection.ALLELE_EXTENSION_LONG_NAME, "2",
                "-ERC", "GVCF"
        };

        runCommandLine(args);

        final Map<Integer, List<Allele>> altAllelesByPosition = VariantContextTestUtils.streamVcf(output)
                .collect(Collectors.toMap(vc -> vc.getStart(), vc -> vc.getAlternateAlleles()));
        for (final VariantContext vc : new FeatureDataSource<VariantContext>(forceCallingVcf)) {
            final List<Allele> altAllelesAtThisLocus = altAllelesByPosition.get(vc.getStart());
            vc.getAlternateAlleles().stream().filter(a-> a.length() > 0 && BaseUtils.isNucleotide(a.getBases()[0])).forEach(a -> Assert.assertTrue(altAllelesAtThisLocus.contains(a)));
        }
    }

    // regression test for https://github.com/broadinstitute/gatk/issues/6495, where a mistake in assembly region trimming
    // caused variants in one-base or similarly short intervals to cause reads to be trimmed to one base long, yielding
    // no calls.
    @Test
    public void testSingleBaseIntervals() throws Exception {
        Utils.resetRandomGenerator();

        final File output = createTempFile("output", ".vcf");

        final ArgumentsBuilder args = new ArgumentsBuilder()
                .addReference(b37Reference)
                .addInput(NA12878_20_21_WGS_bam)
                .addInterval("20:10000117")
                .addInterval("20:10000439")
                .addInterval("20:10000694")
                .addInterval("20:10001019")
                .addOutput(output);

        runCommandLine(args);

        Assert.assertEquals(VariantContextTestUtils.getVariantContexts(output).size(), 4);
    }

    @Test
    public void testBamoutProducesReasonablySizedOutput() {
        final Path bamOutput = createTempFile("testBamoutProducesReasonablySizedOutput", ".bam").toPath();
        innerTestBamoutProducesReasonablySizedOutput(bamOutput);
    }

    @Test(groups={"bucket"})
    public void testBamoutOnGcs() {
        final Path bamOutput = BucketUtils.getPathOnGcs(BucketUtils.getTempFilePath(
            getGCPTestStaging() + "testBamoutProducesReasonablySizedOutput", ".bam"));
        innerTestBamoutProducesReasonablySizedOutput(bamOutput);
    }

    private void innerTestBamoutProducesReasonablySizedOutput(Path bamOutput) {
        Utils.resetRandomGenerator();

        // We will test that when running with -bamout over the testInterval, we produce
        // a bam with a number of reads that is within 10% of what GATK3.5 produces with
        // -bamout over the same interval. This is just to test that we produce a reasonably-sized
        // bam for the region, not to validate the haplotypes, etc. We don't want
        // this test to fail unless there is a likely problem with -bamout itself (eg., empty
        // or truncated bam).
        final String testInterval = "20:10000000-10010000";
        final int gatk3BamoutNumReads = 5000;

        final File vcfOutput = createTempFile("testBamoutProducesReasonablySizedOutput", ".vcf");

        ArgumentsBuilder argBuilder = new ArgumentsBuilder();

        argBuilder.addInput(new File(NA12878_20_21_WGS_bam));
        argBuilder.addReference(new File(b37_reference_20_21));
        argBuilder.addOutput(new File(vcfOutput.getAbsolutePath()));
        argBuilder.add("L", testInterval);
        argBuilder.add(AssemblyBasedCallerArgumentCollection.BAM_OUTPUT_SHORT_NAME, bamOutput.toUri().toString());
        argBuilder.add("pairHMM", "AVX_LOGLESS_CACHING");
        argBuilder.add(AssemblyBasedCallerArgumentCollection.ALLELE_EXTENSION_LONG_NAME,2);

        runCommandLine(argBuilder.getArgsArray());

        try ( final ReadsDataSource bamOutReadsSource = new ReadsPathDataSource(bamOutput) ) {
            int actualBamoutNumReads = 0;
            for ( final GATKRead read : bamOutReadsSource ) {
                ++actualBamoutNumReads;
            }

            final int readCountDifference = Math.abs(actualBamoutNumReads - gatk3BamoutNumReads);
            Assert.assertTrue(((double)readCountDifference / gatk3BamoutNumReads) < 0.10,
                    "-bamout produced a bam with over 10% fewer/more reads than expected");
        }
    }

    @Test
    public void testSitesOnlyMode() {
        Utils.resetRandomGenerator();
        File out = createTempFile("GTStrippedOutput", "vcf");
        final String[] args = {
                "-I", NA12878_20_21_WGS_bam,
                "-R", b37_reference_20_21,
                "-L", "20:10000000-10010000",
                "-O", out.getAbsolutePath(),
                "-pairHMM", "AVX_LOGLESS_CACHING",
                "--" + StandardArgumentDefinitions.SITES_ONLY_LONG_NAME,
                "--" + StandardArgumentDefinitions.ADD_OUTPUT_VCF_COMMANDLINE, "false",
                "--" + AssemblyBasedCallerArgumentCollection.ALLELE_EXTENSION_LONG_NAME, "2"
        };

        runCommandLine(args);

        // Assert that the genotype field has been stripped from the file
        Pair<VCFHeader, List<VariantContext>> results = VariantContextTestUtils.readEntireVCFIntoMemory(out.getAbsolutePath());

        Assert.assertFalse(results.getLeft().hasGenotypingData());
        for (VariantContext v: results.getRight()) {
            Assert.assertFalse(v.hasGenotypes());
        }
    }

    @Test
    public void testForceActiveOption() throws Exception {
        Utils.resetRandomGenerator();
        final File out = createTempFile("GTStrippedOutput", "vcf");
        final File assemblyRegionOut = createTempFile("assemblyregions", ".igv");
        final File expectedAssemblyRegionOut = new File(TEST_FILES_DIR, "expected.testAssemblyRegionWithForceActiveRegions_assemblyregions.igv");

        final String[] args = {
                "-I", NA12878_20_21_WGS_bam,
                "-R", b37_reference_20_21,
                "-L", "20:1-5000",
                "-O", out.getAbsolutePath(),
                "-pairHMM", "AVX_LOGLESS_CACHING",
                "--" + AssemblyBasedCallerArgumentCollection.ALLELE_EXTENSION_LONG_NAME, "2",
                "--" + AssemblyRegionArgumentCollection.FORCE_ACTIVE_REGIONS_LONG_NAME, "true",
                "--" + AssemblyRegionArgumentCollection.ASSEMBLY_REGION_OUT_LONG_NAME, assemblyRegionOut.getAbsolutePath(),
                "--" + StandardArgumentDefinitions.ADD_OUTPUT_VCF_COMMANDLINE, "false"
        };
        runCommandLine(args);

        IntegrationTestSpec.assertEqualTextFiles(assemblyRegionOut, expectedAssemblyRegionOut);
    }

    @DataProvider(name="outputFileVariations")
    public Object[][] getOutputFileVariations() {
        return new Object[][]{
                // bamout index, bamout md5, vcf index, vcf md5
                { true, true, true, true },
                { true, false, true, false },
                { false, true, false, true },
                { false, false, false, false },
        };
    }

    @Test(dataProvider = "outputFileVariations")
    public void testOutputFileArgumentVariations(
            final boolean createBamoutIndex,
            final boolean createBamoutMD5,
            final boolean createVCFOutIndex,
            final boolean createVCFOutMD5) throws IOException {
        Utils.resetRandomGenerator();

        // run on small interval to test index/md5 outputs
        final String testInterval = "20:10000000-10001000";

        final File vcfOutput = createTempFile("testOutputFileArgumentVariations", ".vcf");
        final File bamOutput = createTempFile("testOutputFileArgumentVariations", ".bam");

        ArgumentsBuilder argBuilder = new ArgumentsBuilder();

        argBuilder.addInput(new File(NA12878_20_21_WGS_bam));
        argBuilder.addReference(new File(b37_reference_20_21));
        argBuilder.addOutput(new File(vcfOutput.getAbsolutePath()));
        argBuilder.add("L", testInterval);
        argBuilder.add(AssemblyBasedCallerArgumentCollection.BAM_OUTPUT_SHORT_NAME, bamOutput.getAbsolutePath());
        argBuilder.add("pairHMM", "AVX_LOGLESS_CACHING");
        argBuilder.add(StandardArgumentDefinitions.CREATE_OUTPUT_BAM_INDEX_LONG_NAME, createBamoutIndex);
        argBuilder.add(StandardArgumentDefinitions.CREATE_OUTPUT_BAM_MD5_LONG_NAME, createBamoutMD5);
        argBuilder.add(StandardArgumentDefinitions.CREATE_OUTPUT_VARIANT_INDEX_LONG_NAME, createVCFOutIndex);
        argBuilder.add(StandardArgumentDefinitions.CREATE_OUTPUT_VARIANT_MD5_LONG_NAME, createVCFOutMD5);

        runCommandLine(argBuilder.getArgsArray());

        Assert.assertTrue(vcfOutput.exists(), "No VCF output file was created");

        // validate vcfout companion files
        final File vcfOutFileIndex = new File(vcfOutput.getAbsolutePath() + FileExtensions.TRIBBLE_INDEX);
        final File vcfOutFileMD5 = new File(vcfOutput.getAbsolutePath() + ".md5");
        Assert.assertEquals(vcfOutFileIndex.exists(), createVCFOutIndex, "The index file argument was not honored");
        Assert.assertEquals(vcfOutFileMD5.exists(), createVCFOutMD5, "The md5 file argument was not honored");

        // validate bamout companion files
        if (createBamoutIndex) {
            Assert.assertNotNull(SamFiles.findIndex(bamOutput));
        } else {
            Assert.assertNull(SamFiles.findIndex(bamOutput));
        }

        final File expectedBamoutMD5File = new File(bamOutput.getAbsolutePath() + ".md5");
        Assert.assertEquals(expectedBamoutMD5File.exists(), createBamoutMD5);

        // Check the output BAN header contains all of the inout BAM header Program Records (@PG)
        SamAssertionUtils.assertOutBamContainsInBamProgramRecords(new File(NA12878_20_21_WGS_bam), bamOutput);
    }

    @Test
    public void testHaplotypeCallerRemoveAltAlleleBasedOnHaptypeScores() throws IOException{
        final File testBAM = new File(TEST_FILES_DIR + "pretendTobeTetraPloidTetraAllelicSite.bam");
        final File output = createTempFile("testHaplotypeCallerRemoveAltAlleleBasedOnHaptypeScoresResults", ".vcf");
        final File expected = new File(TEST_FILES_DIR, "expected.testHaplotypeCallerRemoveAltAlleleBasedOnHaptypeScores.gatk4.vcf");

        final String outputPath = UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS ? expected.getAbsolutePath() : output.getAbsolutePath();

        final String[] args = {
                "-I", testBAM.getAbsolutePath(),
                "-R", b37_reference_20_21,
                "-L", "20:11363580-11363600",
                "-O", outputPath,
                "-ploidy", "4",
                "--max-genotype-count", "15",
                "--" + AssemblyBasedCallerArgumentCollection.ALLELE_EXTENSION_LONG_NAME, "2",
                "--" + StandardArgumentDefinitions.ADD_OUTPUT_VCF_COMMANDLINE, "false"
        };
        runCommandLine(args);

        if ( ! UPDATE_EXACT_MATCH_EXPECTED_OUTPUTS ) {
            IntegrationTestSpec.assertEqualTextFiles(output, expected);
        }
    }

    // test that ReadFilterLibrary.NON_ZERO_REFERENCE_LENGTH_ALIGNMENT removes reads that consume zero reference bases
    // e.g. read name HAVCYADXX150109:1:2102:20528:2129 with cigar 23S53I
    @Test
    public void testReadsThatConsumeZeroReferenceReads() throws Exception {
        final String CONSUMES_ZERO_REFERENCE_BASES = publicTestDir + "org/broadinstitute/hellbender/tools/mutect/na12878-chr20-consumes-zero-reference-bases.bam";
        final File outputVcf = createTempFile("output", ".vcf");
        final String[] args = {
                "-I", CONSUMES_ZERO_REFERENCE_BASES,
                "-R", b37_reference_20_21,
                "-O", outputVcf.getAbsolutePath()
        };
        runCommandLine(args);
    }

    // Test fix for https://github.com/broadinstitute/gatk/issues/3466
    // This specifically tests the case of a read that ends up as empty
    // after a call to ReadClipper.hardClipSoftClippedBases()
    @Test
    public void testCompletelyClippedReadNearStartOfContig() throws Exception {
        final File testCaseFilesDir = new File(TEST_FILES_DIR, "issue3466_gatk_cigar_error");
        final File output = createTempFile("testCompletelyClippedReadNearStartOfContig", ".vcf");
        final File expected = new File(testCaseFilesDir, "expected_output_gatk3.vcf");

        final String[] args = {
                "-I", new File(testCaseFilesDir, "culprit.bam").getAbsolutePath(),
                "-R", new File(testCaseFilesDir, "GRCh37_MTonly.fa").getAbsolutePath(),
                "-O", output.getAbsolutePath()
        };
        runCommandLine(args);

        Assert.assertEquals(calculateConcordance(output, expected), 1.0);
    }

    // Test fix for https://github.com/broadinstitute/gatk/issues/3845
    // This specifically tests the case of a read at the start of a contig (position == 1)
    // that becomes completely clipped after a call to ReadClipper.revertSoftClippedBases()
    @Test
    public void testCompletelyClippedReadNearStartOfContig_revertSoftClipped() throws Exception {
        final File testCaseFilesDir = new File(TEST_FILES_DIR, "issue3845_revertSoftClip_bug");
        final File output = createTempFile("testCompletelyClippedReadNearStartOfContig_revertSoftClipped", ".vcf");
        final File expected = new File(testCaseFilesDir, "expected_testCompletelyClippedReadNearStartOfContig_revertSoftClipped_gatk4.vcf");

        final String[] args = {
                "-I", new File(testCaseFilesDir, "issue3845_bug.bam").getAbsolutePath(),
                "-R", new File(publicTestDir, "Homo_sapiens_assembly38_chrM_only.fasta").getAbsolutePath(),
                "-L", "chrM",
                "-O", output.getAbsolutePath()
        };
        runCommandLine(args);

        Assert.assertEquals(calculateConcordance(output, expected), 1.0);
    }

    @Test
    public void testAssemblyRegionAndActivityProfileOutput() throws Exception {
        final File output = createTempFile("testAssemblyRegionAndActivityProfileOutput", ".vcf");
        final File assemblyRegionOut = createTempFile("testAssemblyRegionAndActivityProfileOutput_assemblyregions", ".igv");
        final File expectedAssemblyRegionOut = new File(TEST_FILES_DIR, "expected.testAssemblyRegionAndActivityProfileOutput_assemblyregions.igv");
        final File expectedActivityProfileOut = new File(TEST_FILES_DIR, "expected.testAssemblyRegionAndActivityProfileOutput_activityprofile.igv");

        final String[] args = {
                "-I", NA12878_20_21_WGS_bam,
                "-R", b37_reference_20_21,
                "-L", "20:10000000-10003000",
                "-O", output.getAbsolutePath(),
                "-pairHMM", "AVX_LOGLESS_CACHING",
                "--" + AssemblyRegionArgumentCollection.ASSEMBLY_REGION_OUT_LONG_NAME, assemblyRegionOut.getAbsolutePath()
        };

        runCommandLine(args);

        IntegrationTestSpec.assertEqualTextFiles(assemblyRegionOut, expectedAssemblyRegionOut);
    }

    /*
    * Test that the min_base_quality_score parameter works
    */
    @Test
    public void testMinBaseQualityScore() throws Exception {
        Utils.resetRandomGenerator();

        final File outputAtLowThreshold = createTempFile("output", ".vcf");
        final File outputAtHighThreshold = createTempFile("output", ".vcf");

        final String[] lowThresholdArgs = {
                "-I", NA12878_20_21_WGS_bam,
                "-R", b37_reference_20_21,
                "-L", "20:10000000-10010000",
                "-O", outputAtLowThreshold.getAbsolutePath(),
                "--" + AssemblyBasedCallerArgumentCollection.MIN_BASE_QUALITY_SCORE_LONG_NAME, "20"
        };

        runCommandLine(lowThresholdArgs);

        final String[] highThresholdArgs = {
                "-I", NA12878_20_21_WGS_bam,
                "-R", b37_reference_20_21,
                "-L", "20:10000000-10010000",
                "-O", outputAtHighThreshold.getAbsolutePath(),
                "--" + AssemblyBasedCallerArgumentCollection.MIN_BASE_QUALITY_SCORE_LONG_NAME, "30"
        };

        runCommandLine(highThresholdArgs);

        try (final FeatureDataSource<VariantContext> lowThresholdSource = new FeatureDataSource<VariantContext>(outputAtLowThreshold);
             final FeatureDataSource<VariantContext> highThresholdSource = new FeatureDataSource<VariantContext>(outputAtHighThreshold)) {
            final List<VariantContext> variantsWithLowThreshold =
                    StreamSupport.stream(lowThresholdSource.spliterator(), false).collect(Collectors.toList());

            final List<VariantContext> variantsWithHighThreshold =
                    StreamSupport.stream(highThresholdSource.spliterator(), false).collect(Collectors.toList());

            final Set<Integer> lowStarts = variantsWithLowThreshold.stream().map(VariantContext::getStart).collect(Collectors.toSet());
            final Set<Integer> highStarts = variantsWithHighThreshold.stream().map(VariantContext::getStart).collect(Collectors.toSet());
            lowStarts.removeAll(highStarts);
            final List<Integer> diff = lowStarts.stream().sorted().collect(Collectors.toList());
            Assert.assertEquals(diff, Arrays.asList(10002458, 10008758, 10009842));
        }

    }

    @DataProvider
    public Object[][] getContaminationCorrectionTestData() {
        // This bam is a snippet of GATKBaseTest.NA12878_20_21_WGS_bam artificially contaminated
        // at 15% with reads from another sample. We use such a high contamination fraction to ensure
        // that calls will be noticeably different vs. running without -contamination at all.
        final String contaminatedBam15Percent = largeFileTestDir + "contaminated_bams/CEUTrio.HiSeq.WGS.b37.NA12878.CONTAMINATED.WITH.HCC1143.NORMALS.AT.15PERCENT.bam";

        final SimpleInterval traversalInterval = new SimpleInterval("20", 10100000, 10150000);

        // File specifying the per-sample contamination for contaminatedBam15Percent
        final String contaminationFile = TEST_FILES_DIR + "contamination_file_for_CEUTrio.HiSeq.WGS.b37.NA12878.CONTAMINATED.WITH.HCC1143.NORMALS.AT.15PERCENT";

        // Expected calls from GATK 3.x on the contaminated bam running with -contamination
        // Created in GATK 3.8-1-1-gdde23f56a6 using the command:
        // java -jar target/GenomeAnalysisTK.jar -T HaplotypeCaller -I ../hellbender/src/test/resources/large/contaminated_bams/CEUTrio.HiSeq.WGS.b37.NA12878.CONTAMINATED.WITH.HCC1143.NORMALS.AT.15PERCENT.bam -R ../hellbender/src/test/resources/large/human_g1k_v37.20.21.fasta -L 20:10100000-10150000 -contamination 0.15 -o ../hellbender/src/test/resources/org/broadinstitute/hellbender/tools/haplotypecaller/expected.CEUTrio.HiSeq.WGS.b37.NA12878.CONTAMINATED.WITH.HCC1143.NORMALS.15PCT.20.10100000-10150000.gatk3.8-1-1-gdde23f56a6.vcf
        final String expectedGATK3ContaminationCorrectedCallsVCF = TEST_FILES_DIR + "expected.CEUTrio.HiSeq.WGS.b37.NA12878.CONTAMINATED.WITH.HCC1143.NORMALS.15PCT.20.10100000-10150000.gatk3.8-1-1-gdde23f56a6.vcf";

        // Expected calls from GATK ~4.0.8.1 with indel reference confidence fix
        final String expectedGATK4ContaminationCorrectedCallsGVCF = TEST_FILES_DIR + "expected.CEUTrio.HiSeq.WGS.b37.NA12878.CONTAMINATED.WITH.HCC1143.NORMALS.15PCT.20.10100000-10150000.postIndelRefConfUpdate.g.vcf";

        // Expected calls from GATK 4 on the uncontaminated bam (VCF mode)
        final String expectedGATK4UncontaminatedCallsVCF = TEST_FILES_DIR + "expected.CEUTrio.HiSeq.WGS.b37.NA12878.calls.20.10100000-10150000.vcf";

        // Expected calls from GATK 4 on the uncontaminated bam (GVCF mode)
        final String expectedGATK4UncontaminatedCallsGVCF = TEST_FILES_DIR + "expected.CEUTrio.HiSeq.WGS.b37.NA12878.calls.20.10100000-10150000.g.vcf";

        // bam,
        // known contamination fraction,
        // per-sample contamination file,
        // interval,
        // reference,
        // GVCF mode,
        // GATK 4 calls on the uncontaminated bam
        // GATK 3 calls on the contaminated bam with contamination correction
        return new Object[][] {
                { contaminatedBam15Percent,
                  0.15,
                  contaminationFile,
                  traversalInterval,
                  b37_reference_20_21,
                  false, // VCF mode
                  expectedGATK4UncontaminatedCallsVCF,
                  expectedGATK3ContaminationCorrectedCallsVCF
                },
                { contaminatedBam15Percent,
                  0.15,
                  contaminationFile,
                  traversalInterval,
                  b37_reference_20_21,
                  true, // GVCF mode
                  expectedGATK4UncontaminatedCallsGVCF,
                  expectedGATK4ContaminationCorrectedCallsGVCF
                }
        };
    }

    @Test
    public void testMnpsAreRepresentedAsSingleEvents() {
        final String bam = largeFileTestDir + "contaminated_bams/CEUTrio.HiSeq.WGS.b37.NA12878.CONTAMINATED.WITH.HCC1143.NORMALS.AT.15PERCENT.bam";
        final File outputVcf = createTempFile("output", ".vcf");
        final String[] args = {
                "-I", bam,
                "-R", b37_reference_20_21,
                "-L", "20:10100000-10150000",
                "-O", outputVcf.getAbsolutePath(),
                "--" + HaplotypeCallerArgumentCollection.MAX_MNP_DISTANCE_LONG_NAME, "1"
        };
        Utils.resetRandomGenerator();
        runCommandLine(args);

        final Map<Integer, Allele> altAllelesByPosition = VariantContextTestUtils.streamVcf(outputVcf)
                .collect(Collectors.toMap(VariantContext::getStart, vc -> vc.getAlternateAllele(0)));

        final Map<Integer, Allele> expectedMnps = ImmutableMap.of(
                10102247, Allele.create("AC", false),
                10102530, Allele.create("TG", false),
                10103849, Allele.create("CA", false));

        expectedMnps.entrySet().forEach(entry -> {
            final int position = entry.getKey();
            Assert.assertEquals(altAllelesByPosition.get(position), entry.getValue());
        });
    }

    // test on an artificial bam with several contrived MNPs
    // this test is basically identical to a test in {@ link Mutect2IntegrationTest}
    @Test
    public void testMnps() throws Exception {
        Utils.resetRandomGenerator();
        final File bam = new File(toolsTestDir, "mnp.bam");

        for (final int maxMnpDistance : new int[] {0, 1, 2, 3, 5}) {
            final File outputVcf = createTempFile("unfiltered", ".vcf");

            final List<String> args = Arrays.asList("-I", bam.getAbsolutePath(),
                    "-R", b37_reference_20_21,
                    "-L", "20:10019000-10022000",
                    "-O", outputVcf.getAbsolutePath(),
                    "-" + HaplotypeCallerArgumentCollection.MAX_MNP_DISTANCE_SHORT_NAME, Integer.toString(maxMnpDistance));
            runCommandLine(args);

            checkMnpOutput(maxMnpDistance, outputVcf);
        }
    }

    // this is particular to our particular artificial MNP bam -- we extract a method in order to use it for HaplotypeCaller
    private static void checkMnpOutput(int maxMnpDistance, File outputVcf) {
        // note that for testing HaplotypeCaller GVCF mode we will always have the symbolic <NON REF> allele
        final Map<Integer, List<String>> alleles = VariantContextTestUtils.streamVcf(outputVcf)
                .collect(Collectors.toMap(VariantContext::getStart, vc -> vc.getAlternateAlleles().stream().filter(a -> !a.isSymbolic()).map(Allele::getBaseString).collect(Collectors.toList())));

        // phased, two bases apart
        if (maxMnpDistance < 2) {
            Assert.assertEquals(alleles.get(10019968), Arrays.asList("G"));
            Assert.assertEquals(alleles.get(10019970), Arrays.asList("G"));
        } else {
            Assert.assertEquals(alleles.get(10019968), Arrays.asList("GAG"));
            Assert.assertTrue(!alleles.containsKey(10019970));
        }

        // adjacent and out of phase
        Assert.assertEquals(alleles.get(10020229), Arrays.asList("A"));
        Assert.assertEquals(alleles.get(10020230), Arrays.asList("G"));

        // 4-substitution MNP w/ spacings 2, 3, 4
        if (maxMnpDistance < 2) {
            Assert.assertEquals(alleles.get(10020430), Arrays.asList("G"));
            Assert.assertEquals(alleles.get(10020432), Arrays.asList("G"));
            Assert.assertEquals(alleles.get(10020435), Arrays.asList("G"));
            Assert.assertEquals(alleles.get(10020439), Arrays.asList("G"));
        } else if (maxMnpDistance < 3) {
            Assert.assertEquals(alleles.get(10020430), Arrays.asList("GAG"));
            Assert.assertEquals(alleles.get(10020435), Arrays.asList("G"));
            Assert.assertEquals(alleles.get(10020439), Arrays.asList("G"));
        } else if (maxMnpDistance < 4) {
            Assert.assertEquals(alleles.get(10020430), Arrays.asList("GAGTTG"));
            Assert.assertEquals(alleles.get(10020439), Arrays.asList("G"));
        } else {
            Assert.assertEquals(alleles.get(10020430), Arrays.asList("GAGTTGTCTG"));
        }

        // two out of phase DNPs that overlap and have a base in common
        if (maxMnpDistance > 0) {
            Assert.assertEquals(alleles.get(10020680), Arrays.asList("TA"));
            Assert.assertEquals(alleles.get(10020681), Arrays.asList("AT"));
        }
    }

    @Test(dataProvider = "getContaminationCorrectionTestData")
    public void testContaminationCorrection( final String contaminatedBam,
                                   final double contaminationFraction,
                                   final String contaminationFile,
                                   final SimpleInterval interval,
                                   final String reference,
                                   final boolean gvcfMode,
                                   final String gatk4UncontaminatedCallsVCF,
                                   final String expectedContaminationCorrectedCallsVCF ) throws Exception {
        final File uncorrectedOutput = createTempFile("testContaminationCorrectionUncorrectedOutput", gvcfMode ? ".g.vcf" : ".vcf");
        final File correctedOutput = createTempFile("testContaminationCorrectionCorrectedOutput", gvcfMode ? ".g.vcf" : ".vcf");
        final File correctedOutputUsingContaminationFile = createTempFile("testContaminationCorrectionCorrectedOutputUsingContaminationFile", gvcfMode ? ".g.vcf" : ".vcf");

        // Generate raw uncorrected calls on the contaminated bam, for comparison purposes
        // Note that there are a huge number of MNPs in this bam, and that in {@code gatk4UncontaminatedCallsVCF} and
        // {@code expectedContaminationCorrectedCallsVCF} these are represented as independent consecutive SNPs
        // Thus if we ever turn on MNPs by default, this will fail
        final String[] noContaminationCorrectionArgs = {
                "-I", contaminatedBam,
                "-R", reference,
                "-L", interval.toString(),
                "-O", uncorrectedOutput.getAbsolutePath(),
                "--" + AssemblyBasedCallerArgumentCollection.EMIT_REF_CONFIDENCE_LONG_NAME, (gvcfMode ? ReferenceConfidenceMode.GVCF.toString() : ReferenceConfidenceMode.NONE.toString()),
                "--" + AssemblyBasedCallerArgumentCollection.ALLELE_EXTENSION_LONG_NAME, "2",
        };
        Utils.resetRandomGenerator();
        runCommandLine(noContaminationCorrectionArgs);

        // Run with contamination correction using -contamination directly
        final String[] contaminationCorrectionArgs = {
                "-I", contaminatedBam,
                "-R", reference,
                "-L", interval.toString(),
                "-O", correctedOutput.getAbsolutePath(),
                "-contamination", Double.toString(contaminationFraction),
                "--" + AssemblyBasedCallerArgumentCollection.EMIT_REF_CONFIDENCE_LONG_NAME, (gvcfMode ? ReferenceConfidenceMode.GVCF.toString() : ReferenceConfidenceMode.NONE.toString()),
                "--" + AssemblyBasedCallerArgumentCollection.ALLELE_EXTENSION_LONG_NAME, "2",
        };
        Utils.resetRandomGenerator();
        runCommandLine(contaminationCorrectionArgs);

        // Run with contamination correction using a contamination file
        final String[] contaminationCorrectionFromFileArgs = {
                "-I", contaminatedBam,
                "-R", reference,
                "-L", interval.toString(),
                "-O", correctedOutputUsingContaminationFile.getAbsolutePath(),
                "-contamination-file", contaminationFile,
                "--" + AssemblyBasedCallerArgumentCollection.EMIT_REF_CONFIDENCE_LONG_NAME, (gvcfMode ? ReferenceConfidenceMode.GVCF.toString() : ReferenceConfidenceMode.NONE.toString()),
                "--" + AssemblyBasedCallerArgumentCollection.ALLELE_EXTENSION_LONG_NAME, "2",
        };
        Utils.resetRandomGenerator();
        runCommandLine(contaminationCorrectionFromFileArgs);

        // Calculate concordance vs. the calls on the uncontaminated bam. Count only actual calls
        // (ignoring GVCF reference blocks, if present) for the purposes of the concordance calculation.
        final double uncorrectedCallsVSUncontaminatedCallsConcordance = calculateConcordance(uncorrectedOutput, new File(gatk4UncontaminatedCallsVCF), gvcfMode);
        final double correctedCallsVSUncontaminatedCallsConcordance = calculateConcordance(correctedOutput, new File(gatk4UncontaminatedCallsVCF), gvcfMode);
        final double correctedCallsFromFileVSUncontaminatedCallsConcordance = calculateConcordance(correctedOutputUsingContaminationFile, new File(gatk4UncontaminatedCallsVCF), gvcfMode);

        // Calculate concordance vs. the contamination-corrected calls from GATK3. Here we count
        // all records in the output, including reference blocks.
        final double correctedCallsVSExpectedCorrectedCallsConcordance = calculateConcordance(correctedOutput, new File(expectedContaminationCorrectedCallsVCF));
        final double correctedCallsFromFileVSExpectedCorrectedCallsConcordance = calculateConcordance(correctedOutputUsingContaminationFile, new File(expectedContaminationCorrectedCallsVCF));

        // Sanity checks: the concordance when running with -contamination should be the same as the
        // concordance when running with -contamination-file
        assertEqualsDoubleSmart(correctedCallsVSUncontaminatedCallsConcordance, correctedCallsFromFileVSUncontaminatedCallsConcordance, 0.001,
                "concordance running with -contamination and -contamination-file should be identical, but wasn't");
        assertEqualsDoubleSmart(correctedCallsVSExpectedCorrectedCallsConcordance, correctedCallsFromFileVSExpectedCorrectedCallsConcordance, 0.001,
                "concordance running with -contamination and -contamination-file should be identical, but wasn't");

        // With -contamination correction on, concordance vs. the calls on the uncontaminated bam
        // should improve by at least 1% (actual improvement tends to be much larger, but we just
        // want to make sure that the calls don't actually get worse here)
        Assert.assertTrue(correctedCallsVSUncontaminatedCallsConcordance > uncorrectedCallsVSUncontaminatedCallsConcordance + 0.01,
                "running with -contamination should have improved concordance vs. calls on the uncontaminated bam by at least 1%, but it didn't");

        // With -contamination-file correction on, concordance vs. the calls on the uncontaminated bam
        // should improve by at least 1% (actual improvement tends to be much larger, but we just
        // want to make sure that the calls don't actually get worse here)
        Assert.assertTrue(correctedCallsFromFileVSUncontaminatedCallsConcordance > uncorrectedCallsVSUncontaminatedCallsConcordance + 0.01,
                "running with -contamination-file should have improved concordance vs. calls on the uncontaminated bam by at least 1%, but it didn't");

        // -contamination corrected output in GATK4 should have >= 99% concordance
        // vs. -contamination corrected output in GATK3
        Assert.assertTrue(correctedCallsVSExpectedCorrectedCallsConcordance >= 0.99,
                "output with -contamination correction should have >= 99% concordance with contamination-corrected calls from GATK3, but it didn't");

        // -contamination-file corrected output in GATK4 should have >= 99% concordance
        // vs. -contamination corrected output in GATK3
        Assert.assertTrue(correctedCallsFromFileVSExpectedCorrectedCallsConcordance >= 0.99,
                "output with -contamination-file correction should have >= 99% concordance with contamination-corrected calls from GATK3, but it didn't");
    }

    // With 100% contamination in VCF mode, make sure that we get no calls
    @Test
    public void test100PercentContaminationNoCallsInVCFMode() throws Exception {
        final File output = createTempFile("test100PercentContaminationNoCallsInVCFMode", ".vcf");

        final String[] contaminationArgs = {
                "-I", NA12878_20_21_WGS_bam,
                "-R", b37_reference_20_21,
                "-L", "20:10000000-10010000",
                "-O", output.getAbsolutePath(),
                "-contamination", "1.0"
        };
        runCommandLine(contaminationArgs);

        final Pair<VCFHeader, List<VariantContext>> result = VariantContextTestUtils.readEntireVCFIntoMemory(output.getAbsolutePath());

        // Check that we get no calls, but a non-empty VCF header.
        Assert.assertTrue(result.getLeft().getMetaDataInInputOrder().size() > 0, "There should be a non-empty header present");
        Assert.assertEquals(result.getRight().size(), 0, "There should be no calls with 100% contamination in VCF mode");
    }

    // With 100% contamination in GVCF mode, make sure that we get no calls (only reference blocks)
    @Test
    public void test100PercentContaminationNoCallsInGVCFMode() throws Exception {
        final File output = createTempFile("test100PercentContaminationNoCallsInGVCFMode", ".g.vcf");

        final String[] contaminationArgs = {
                "-I", NA12878_20_21_WGS_bam,
                "-R", b37_reference_20_21,
                "-L", "20:10000000-10010000",
                "-O", output.getAbsolutePath(),
                "-contamination", "1.0",
                "--" + AssemblyBasedCallerArgumentCollection.EMIT_REF_CONFIDENCE_LONG_NAME, ReferenceConfidenceMode.GVCF.toString(),
                "--" + AssemblyBasedCallerArgumentCollection.ALLELE_EXTENSION_LONG_NAME, "2",
        };
        runCommandLine(contaminationArgs);

        final Pair<VCFHeader, List<VariantContext>> result = VariantContextTestUtils.readEntireVCFIntoMemory(output.getAbsolutePath());

        // Check that we get a non-empty VCF header.
        Assert.assertTrue(result.getLeft().getMetaDataInInputOrder().size() > 0, "There should be a non-empty header present");

        // Check that we get only GVCF reference blocks, and no actual calls
        final List<VariantContext> vcfRecords = result.getRight();
        Assert.assertTrue(! vcfRecords.isEmpty(), "VCF should be non-empty in GVCF mode");
        for ( final VariantContext vc : vcfRecords ) {
            Assert.assertTrue(isGVCFReferenceBlock(vc), "Expected only GVCF reference blocks (no actual calls)");
        }
    }

    // check that a single, errorful, read that induces a cycle does not cause an assembly region to lose a real variant
    @Test
    public void testPrunedCycle() throws Exception {
        final File output = createTempFile("output", ".vcf");

        final String[] args = {
                "-I", TEST_FILES_DIR + "pruned_cycle.bam",
                "-R", b37Reference,
                "-L", "1:169510380",
                "--" + IntervalArgumentCollection.INTERVAL_PADDING_LONG_NAME, "100",
                "--" + AssemblyBasedCallerArgumentCollection.ALLELE_EXTENSION_LONG_NAME, "2",
                "-O", output.getAbsolutePath()
        };
        runCommandLine(args);

        final Optional<VariantContext> het = VariantContextTestUtils.streamVcf(output)
                .filter(vc -> vc.getStart() == 169510380)
                .findFirst();

        Assert.assertTrue(het.isPresent());
        Assert.assertTrue(het.get().getGenotype(0).getAD()[1] > 100);
    }

    @DataProvider
    public Object[][] getMaxAlternateAllelesData() {
        return new Object[][] {
                // bam, reference, interval string, max alternate alleles, GVCF mode toggle
                { NA12878_20_21_WGS_bam, b37_reference_20_21, "20:10008000-10010000", 1, false },
                { NA12878_20_21_WGS_bam, b37_reference_20_21, "20:10002000-10011000", 1, true },
                { NA12878_20_21_WGS_bam, b37_reference_20_21, "20:10002000-10011000", 2, true }
        };
    }

    /*
     * Test for the --max-alternate-alleles argument
     */
    @Test(dataProvider = "getMaxAlternateAllelesData")
    public void testMaxAlternateAlleles(final String bam, final String reference, final String intervalString,
                                        final int maxAlternateAlleles, final boolean gvcfMode) {
        final File outputNoMaxAlternateAlleles = createTempFile("testMaxAlternateAllelesNoMaxAlternateAlleles", (gvcfMode ? ".g.vcf" : ".vcf"));
        final File outputWithMaxAlternateAlleles = createTempFile("testMaxAlternateAllelesWithMaxAlternateAlleles", (gvcfMode ? ".g.vcf" : ".vcf"));

        // Run both with and without --max-alternate-alleles over our interval, so that we can
        // prove that the argument is working as intended.
        final String[] argsNoMaxAlternateAlleles = {
                "-I", bam,
                "-R", reference,
                "-L", intervalString,
                "-O", outputNoMaxAlternateAlleles.getAbsolutePath(),
                "--" + AssemblyBasedCallerArgumentCollection.EMIT_REF_CONFIDENCE_LONG_NAME, (gvcfMode ? ReferenceConfidenceMode.GVCF.toString() : ReferenceConfidenceMode.NONE.toString()),
                "--" + AssemblyBasedCallerArgumentCollection.ALLELE_EXTENSION_LONG_NAME, "2",
        };
        runCommandLine(argsNoMaxAlternateAlleles);

        final String[] argsWithMaxAlternateAlleles = {
                "-I", bam,
                "-R", reference,
                "-L", intervalString,
                "-O", outputWithMaxAlternateAlleles.getAbsolutePath(),
                "--max-alternate-alleles", Integer.toString(maxAlternateAlleles),
                "--" + AssemblyBasedCallerArgumentCollection.EMIT_REF_CONFIDENCE_LONG_NAME, (gvcfMode ? ReferenceConfidenceMode.GVCF.toString() : ReferenceConfidenceMode.NONE.toString()),
                "--" + AssemblyBasedCallerArgumentCollection.ALLELE_EXTENSION_LONG_NAME, "2",
        };
        runCommandLine(argsWithMaxAlternateAlleles);

        final List<VariantContext> callsNoMaxAlternateAlleles = VariantContextTestUtils.readEntireVCFIntoMemory(outputNoMaxAlternateAlleles.getAbsolutePath()).getRight();
        final List<VariantContext> callsWithMaxAlternateAlleles = VariantContextTestUtils.readEntireVCFIntoMemory(outputWithMaxAlternateAlleles.getAbsolutePath()).getRight();

        // First, find all calls in the VCF produced WITHOUT --max-alternate-alleles that have
        // more than maxAlternateAlleles alt alleles, excluding NON_REF. For each call, calculate
        // and store the expected list of subsetted alleles:
        final Map<SimpleInterval, List<Allele>> expectedSubsettedAllelesByLocus = new HashMap<>();
        for ( final VariantContext vc : callsNoMaxAlternateAlleles ) {
            if ( getNumAltAllelesExcludingNonRef(vc) > maxAlternateAlleles ) {
                final List<Allele> mostLikelyAlleles = AlleleSubsettingUtils.calculateMostLikelyAlleles(vc, HomoSapiensConstants.DEFAULT_PLOIDY, maxAlternateAlleles);
                expectedSubsettedAllelesByLocus.put(new SimpleInterval(vc), mostLikelyAlleles);
            }
        }

        // Then assert that we saw at least one call with more than maxAlternateAlleles alt alleles
        // when running without --max-alternate-alleles (otherwise, the tests below won't be meaningful):
        Assert.assertTrue(! expectedSubsettedAllelesByLocus.isEmpty(),
                "Without --max-alternate-alleles, there should be at least one call in the output with more than " + maxAlternateAlleles +
                        " alt alleles in order for this test to be meaningful");

        // Now assert that in the VCF produced WITH --max-alternate-alleles, there are no calls with
        // more than maxAlternateAlleles alt alleles, excluding NON_REF. Also check each call that would
        // have had more than maxAlternateAlleles alleles against the expected list of subsetted alleles,
        // to ensure that we selected the most likely alleles:
        for ( final VariantContext vc : callsWithMaxAlternateAlleles ) {

            // No call should have more than the configured number of alt alleles (excluding NON_REF)
            Assert.assertTrue(getNumAltAllelesExcludingNonRef(vc) <= maxAlternateAlleles,
                    "Number of alt alleles exceeds --max-alternate-alleles " + maxAlternateAlleles + " for VariantContext: " + vc);

            // If there's an entry for this locus in our table of expected alleles post-subsetting, assert
            // that we selected the right alleles during subsetting.
            List<Allele> alleleSubsettingExpectedResult = expectedSubsettedAllelesByLocus.get(new SimpleInterval(vc));
            if ( alleleSubsettingExpectedResult != null ) {

                // CollectionUtils.isEqualCollection() will compare the lists of Alleles without
                // regard to ordering
                Assert.assertTrue(CollectionUtils.isEqualCollection(vc.getAlleles(), alleleSubsettingExpectedResult),
                        "For call " + vc + " expected alleles after subsetting were: " + alleleSubsettingExpectedResult +
                                 " but instead found alleles: " + vc.getAlleles());
            }

            // For completeness sake, also check the genotypes to ensure that no genotypes reference
            // an allele not present in the VC:
            for ( final Genotype genotype : vc.getGenotypes() ) {
                if ( genotype.isAvailable() ) {
                    for ( final Allele genotypeAllele : genotype.getAlleles() ) {
                        if ( genotypeAllele.isCalled() ) {
                            Assert.assertTrue(vc.hasAllele(genotypeAllele),
                                    "Allele " + genotypeAllele + " was present in genotype " + genotype +
                                            " but not in the VariantContext itself");
                        }
                    }
                }
            }
        }
    }

    @Test
    public void testContaminatedHomVarDeletions() {
        final String bam = toolsTestDir + "haplotypecaller/deletion_sample.snippet.bam";
        final String intervals = "chr3:46373452";

        final File outputContaminatedHomVarDeletions = createTempFile("testContaminatedHomVarDeletions", ".vcf");

        final ArgumentsBuilder args = new ArgumentsBuilder().addInput(new File(bam))
                .addReference(hg38Reference)
                .addInterval(new SimpleInterval(intervals))
                .addOutput(outputContaminatedHomVarDeletions)
                .add(IntervalArgumentCollection.INTERVAL_PADDING_LONG_NAME, 50);
        runCommandLine(args);

        List<VariantContext> vcs = VariantContextTestUtils.getVariantContexts(outputContaminatedHomVarDeletions);

        //check known homozygous deletion for correct genotype
        for (final VariantContext vc : vcs) {
            final Genotype gt = vc.getGenotype(0);
            if (gt.hasAD()) {
                final int[] ads = gt.getAD();
                final double alleleBalance = ads[1] / (ads[0] + ads[1]);
                if (alleleBalance > 0.9) {
                    Assert.assertTrue(vc.getGenotype(0).isHomVar());
                }
            }
        }
    }

    // the order of adjacent deletion and insertion events is arbitary, hence the locus of an insertion can equally well
    // be assigned to the beginning or end of an adjacent deletion.  However, assigning to the beginning of the deletion
    // creates a single event that looks like a MNP or complicated event eg ACG -> TTGT.  There is nothing wrong with this -- indeed
    // it is far preferable to calling two events -- however GenomicsDB as of March 2020 does not support MNPs.  Once it does,
    // this test will not be necessary.
    @Test
    public void testAdjacentIndels() {
        final File bam = new File(TEST_FILES_DIR, "issue_6473_adjacent_indels.bam");
        final String interval = "17:7578000-7578500";

        final File output = createTempFile("output", ".vcf");

        final ArgumentsBuilder args = new ArgumentsBuilder()
                .addInput(bam)
                .addReference(b37Reference)
                .addInterval(interval)
                .addOutput(output);
        runCommandLine(args);

        Assert.assertTrue(VariantContextTestUtils.streamVcf(output)
                .allMatch(vc -> vc.isBiallelic() && (vc.getReference().length() == 1 || vc.getAlternateAllele(0).length() == 1)));
    }

    /*
    Prior to IUPAC ReadTransformer fix, this test yields
    java.lang.IllegalArgumentException: Unexpected base in allele bases 'ATTCATTTCACAAGGGTAAAGCTTTCTTTGGATTCAGCAGGTTGGAAAATCTGTTTTTCACCTTTCTGTGAATGGACGTTTGGGAGCTCATTGAGGCCAGTGRCAATAAAGGAGATATCTCAGGGTGAAAAATAAAAGACAGGAATGTGAGAATTGGCTTTGTGATGTGAGCATTCATTTCACAAAGTTAAACCTTTCTTTTCATTCAGCAGTTAGAAATCACTGGTTTTGTAGAATCTG'
    where there's an R in that big long string representing the assembled haplotype.  The R is in the hg38 reference and
    gets propagated into cram reads.
     */
    @Test
    public void testAdjacentIUPACBasesinReads() {
        final File bam = new File(TEST_FILES_DIR, "cramWithR.cram");
        final String interval = "chr10:39239400-39239523";

        final File output = createTempFile("output", ".vcf");

        final ArgumentsBuilder args = new ArgumentsBuilder()
                .addInput(bam)
                .addReference(hg38Reference)
                .addInterval(interval)
                .addOutput(output)
                //We can check the warning in the test log provided we're explicit about the logging level
                .add(StandardArgumentDefinitions.VERBOSITY_NAME, Log.LogLevel.WARNING.name());
        runCommandLine(args);

        final List<VariantContext> outputVCs = VariantContextTestUtils.readEntireVCFIntoMemory(output.getAbsolutePath()).getRight();
        Assert.assertEquals(outputVCs.size(), 1);
        Assert.assertEquals(outputVCs.get(0).getStart(), 39239403);
        Assert.assertEquals(outputVCs.get(0).getAlternateAllele(0).getBaseString(), "A");
    }

    // this test has a reference with 8 repeats of a 28-mer and an alt with 7 repeats.  This deletion left-aligns to the
    // beginning of the padded assembly region, and an exception occurs if we carelessly drop the leading deletion from
    // the alt haplotype's cigar.  This is a regression test for https://github.com/broadinstitute/gatk/issues/6533.
    // In addition to testing that no error is thrown, we also check that the 28-base deletion is called
    @Test
    public void testLeadingDeletionInAltHaplotype() {
        final File bam = new File(TEST_FILES_DIR, "alt-haplotype-leading-deletion.bam");

        final File output = createTempFile("output", ".vcf");

        final ArgumentsBuilder args = new ArgumentsBuilder()
                .addInput(bam)
                .addReference(b37Reference)
                .addInterval("10:128360000-128362000")
                .addOutput(output);
        runCommandLine(args);

        final boolean has28BaseDeletion = VariantContextTestUtils.streamVcf(output)
                .anyMatch(vc -> vc.getStart() == 128361367 && vc.getAlternateAlleles().stream().anyMatch(a -> a.length() == vc.getReference().length() - 28));

        Assert.assertTrue(has28BaseDeletion);
    }

    /**
     * Helper method for testMaxAlternateAlleles
     *
     * @param vc VariantContext to check
     * @return number of alt alleles in vc, excluding NON_REF (if present)
     */
    private int getNumAltAllelesExcludingNonRef( final VariantContext vc ) {
        final List<Allele> altAlleles = vc.getAlternateAlleles();
        int numAltAllelesExcludingNonRef = 0;

        for ( final Allele altAllele : altAlleles ) {
            if ( ! altAllele.equals(Allele.NON_REF_ALLELE) ) {
                ++numAltAllelesExcludingNonRef;
            }
        }

        return numAltAllelesExcludingNonRef;
    }

    /*
     * Calculate rough concordance between two vcfs, comparing only the positions, alleles, and the first genotype.
     */
    public static double calculateConcordance( final File actual, final File expected ) {
        return calculateConcordance(actual, expected, false);
    }

    /*
     * Calculate rough concordance between two vcfs, comparing only the positions, alleles, and the first genotype.
     * Can optionally ignore GVCF blocks in the concordance calculation.
     */
    public static double calculateConcordance( final File actual, final File expected, final boolean ignoreGVCFBlocks ) {
        final Set<String> actualVCFKeys = new HashSet<>();
        final Set<String> expectedVCFKeys = new HashSet<>();
        int concordant = 0;
        int discordant = 0;

        // unzip to avoid https://github.com/broadinstitute/gatk/issues/4224
        File actualUnzipped = IOUtils.gunzipToTempIfNeeded(actual);
        try ( final FeatureDataSource<VariantContext> actualSource = new FeatureDataSource<>(actualUnzipped);
              final FeatureDataSource<VariantContext> expectedSource = new FeatureDataSource<>(expected) ) {

            for ( final VariantContext vc : actualSource ) {
                if ( ! ignoreGVCFBlocks || ! isGVCFReferenceBlock(vc) ) {
                    actualVCFKeys.add(keyForVariant(vc));
                }
            }

            for ( final VariantContext vc : expectedSource ) {
                if ( ! ignoreGVCFBlocks || ! isGVCFReferenceBlock(vc) ) {
                    expectedVCFKeys.add(keyForVariant(vc));
                }
            }

            for ( final String vcKey : actualVCFKeys ) {
                if ( ! expectedVCFKeys.contains(vcKey) ) {
                    ++discordant;
                }
                else {
                    ++concordant;
                }
            }

            for ( final String vcKey : expectedVCFKeys ) {
                if ( ! actualVCFKeys.contains(vcKey) ) {
                    ++discordant;
                }
            }
        }

        return (double)concordant / (double)(concordant + discordant);
    }

    private static String keyForVariant( final VariantContext variant ) {
        Genotype genotype = variant.getGenotype(0);
        if (genotype.isPhased()) { // unphase it for comparisons, since we rely on comparing the genotype string below
            genotype = new GenotypeBuilder(genotype).phased(false).make();
        }
        return String.format("%s:%d-%d %s %s", variant.getContig(), variant.getStart(), variant.getEnd(),
                variant.getAlleles(), genotype.getGenotypeString(false));
    }

    private static boolean isGVCFReferenceBlock( final VariantContext vc ) {
        return vc.hasAttribute(VCFConstants.END_KEY) &&
               vc.getAlternateAlleles().size() == 1 &&
               vc.getAlternateAllele(0).equals(Allele.NON_REF_ALLELE);
    }
}
