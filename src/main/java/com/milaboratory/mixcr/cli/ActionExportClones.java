/*
 * Copyright (c) 2014-2015, Bolotin Dmitry, Chudakov Dmitry, Shugay Mikhail
 * (here and after addressed as Inventors)
 * All Rights Reserved
 *
 * Permission to use, copy, modify and distribute any part of this program for
 * educational, research and non-profit purposes, by non-profit institutions
 * only, without fee, and without a written agreement is hereby granted,
 * provided that the above copyright notice, this paragraph and the following
 * three paragraphs appear in all copies.
 *
 * Those desiring to incorporate this work into commercial products or use for
 * commercial purposes should contact the Inventors using one of the following
 * email addresses: chudakovdm@mail.ru, chudakovdm@gmail.com
 *
 * IN NO EVENT SHALL THE INVENTORS BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
 * SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
 * ARISING OUT OF THE USE OF THIS SOFTWARE, EVEN IF THE INVENTORS HAS BEEN
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * THE SOFTWARE PROVIDED HEREIN IS ON AN "AS IS" BASIS, AND THE INVENTORS HAS
 * NO OBLIGATION TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR
 * MODIFICATIONS. THE INVENTORS MAKES NO REPRESENTATIONS AND EXTENDS NO
 * WARRANTIES OF ANY KIND, EITHER IMPLIED OR EXPRESS, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY OR FITNESS FOR A
 * PARTICULAR PURPOSE, OR THAT THE USE OF THE SOFTWARE WILL NOT INFRINGE ANY
 * PATENT, TRADEMARK OR OTHER RIGHTS.
 */
package com.milaboratory.mixcr.cli;

import cc.redberry.primitives.Filter;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.milaboratory.core.sequence.AminoAcidSequence;
import com.milaboratory.core.sequence.NSequenceWithQuality;
import com.milaboratory.mixcr.basictypes.*;
import com.milaboratory.mixcr.export.InfoWriter;
import com.milaboratory.util.CanReportProgressAndStage;
import com.milaboratory.util.SmartProgressReporter;
import io.repseq.core.Chains;
import io.repseq.core.GeneFeature;
import io.repseq.core.GeneType;
import io.repseq.core.VDJCLibraryRegistry;

import java.io.InputStream;
import java.util.List;

public class ActionExportClones extends ActionExport {
    public ActionExportClones() {
        super(new CloneExportParameters(), Clone.class);
    }

    @Override
    public void go0() throws Exception {
        CloneExportParameters parameters = (CloneExportParameters) this.parameters;
        try (InputStream inputStream = IOUtil.createIS(parameters.getInputFile());
             InfoWriter<Clone> writer = new InfoWriter<>(parameters.getOutputFile())) {
            CloneSet set = CloneSetIO.read(inputStream, VDJCLibraryRegistry.getDefault());

            if (parameters.loci_legacy != null) {
                System.out.println("WARNING: using of -l (--loci) option is deprecated; use -c (--chains) instead.");
                parameters.loci = parameters.loci_legacy;
            }
            
            if (parameters.filterOutOfFrames || parameters.filterStops || !"all".equals(parameters.loci))
                set = CloneSet.transform(set, new CFilter(parameters.filterOutOfFrames, parameters.filterStops,
                        parameters.getLoci()));

            writer.attachInfoProviders((List) parameters.exporters);
            long limit = parameters.getLimit();
            for (int i = 0; i < set.size(); i++) {
                if (set.get(i).getFraction() < parameters.minFraction ||
                        set.get(i).getCount() < parameters.minCount) {
                    limit = i;
                    break;
                }
            }
            ExportClones exportClones = new ExportClones(set, writer, limit);
            SmartProgressReporter.startProgressReport(exportClones, System.err);
            exportClones.run();
        }
    }

    @Override
    public String command() {
        return "exportClones";
    }

    private static final class CFilter implements Filter<Clone> {
        final boolean filterOutOfFrames, filterStopCodons;
        final Chains chains;

        public CFilter(boolean filterOutOfFrames, boolean filterStopCodons,
                       Chains chains) {
            this.filterOutOfFrames = filterOutOfFrames;
            this.filterStopCodons = filterStopCodons;
            this.chains = chains;
        }

        @Override
        public boolean accept(Clone clone) {
            if (filterOutOfFrames) {
                NSequenceWithQuality cdr3 = clone.getFeature(GeneFeature.CDR3);
                if (cdr3 == null || cdr3.size() % 3 != 0)
                    return false;
            }
            if (filterStopCodons)
                for (int i = 0; i < clone.numberOfTargets(); i++)
                    if (AminoAcidSequence.translateFromCenter(clone.getTarget(i).getSequence()).containStops())
                        return false;

            if (chains != null) {
                boolean ok = false;
                VDJCHit h = clone.getBestHit(GeneType.Variable);
                if (h != null && h.getGene().getChains().intersects(chains))
                    ok = true;

                h = clone.getBestHit(GeneType.Joining);
                if (h != null && h.getGene().getChains().intersects(chains))
                    ok = true;

                if (!ok)
                    return false;
            }

            return true;
        }
    }

    @Parameters(commandDescription = "Export clones to tab-delimited text file")
    public static final class ExportClones implements CanReportProgressAndStage {
        final static String stage = "Exporting clones";
        final CloneSet clones;
        final InfoWriter<Clone> writer;
        final long size;
        volatile long current = 0;
        final long limit;

        private ExportClones(CloneSet clones, InfoWriter<Clone> writer, long limit) {
            this.clones = clones;
            this.writer = writer;
            this.size = clones.size();
            this.limit = limit;
        }

        @Override
        public String getStage() {
            return stage;
        }

        @Override
        public double getProgress() {
            return (1.0 * current) / size;
        }

        @Override
        public boolean isFinished() {
            return current == size;
        }

        void run() {
            for (Clone clone : clones.getClones()) {
                if (current == limit)
                    break;
                writer.put(clone);
                ++current;
            }
        }
    }

    public static class CloneExportParameters extends ActionExportParameters {
        @Parameter(description = "Exclude clones with out-of-frame clone sequences (fractions will be recalculated)",
                names = {"-o", "--filter-out-of-frames"})
        public Boolean filterOutOfFrames = false;

        @Parameter(description = "Exclude sequences containing stop codons (fractions will be recalculated)",
                names = {"-t", "--filter-stops"})
        public Boolean filterStops = false;


        @Parameter(description = "Limit export to specific chain (e.g. TRA or IGH) (fractions will be recalculated)",
                names = {"-c", "--chains"})
        public String loci = "ALL";

        @Deprecated
        @Parameter(description = "Limit export to specific chain (e.g. TRA or IGH) (fractions will be recalculated)",
                names = {"-l", "--loci"}, hidden = true)
        public String loci_legacy = null;

        @Parameter(description = "Filter clones by minimal clone fraction",
                names = {"-q", "--minimal-clone-fraction"})
        public float minFraction = 0;

        @Parameter(description = "Filter clones by minimal clone read count",
                names = {"-m", "--minimal-clone-count"})
        public long minCount = 0;

        public Chains getLoci() {
            return Util.parseLoci(loci);
        }
    }
}
