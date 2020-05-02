/**
 * This code is generated using [[https://www.scala-sbt.org/contraband/ sbt-contraband]].
 */

// DO NOT EDIT MANUALLY
package xsbti.compile;
/** The result of running the incremental compilation. */
public final class CompileResult implements xsbti.compile.AnalysisContents, java.io.Serializable {
    public xsbti.compile.CompileAnalysis getAnalysis() { return this.analysis; }
    public xsbti.compile.MiniSetup getMiniSetup() { return this.setup; }
    public static CompileResult create(xsbti.compile.CompileAnalysis _analysis, xsbti.compile.MiniSetup _setup, boolean _hasModified) {
        return new CompileResult(_analysis, _setup, _hasModified);
    }
    public static CompileResult of(xsbti.compile.CompileAnalysis _analysis, xsbti.compile.MiniSetup _setup, boolean _hasModified) {
        return new CompileResult(_analysis, _setup, _hasModified);
    }
    private xsbti.compile.CompileAnalysis analysis;
    private xsbti.compile.MiniSetup setup;
    private boolean hasModified;
    protected CompileResult(xsbti.compile.CompileAnalysis _analysis, xsbti.compile.MiniSetup _setup, boolean _hasModified) {
        super();
        analysis = _analysis;
        setup = _setup;
        hasModified = _hasModified;
    }
    
    public xsbti.compile.CompileAnalysis analysis() {
        return this.analysis;
    }
    public xsbti.compile.MiniSetup setup() {
        return this.setup;
    }
    public boolean hasModified() {
        return this.hasModified;
    }
    public CompileResult withAnalysis(xsbti.compile.CompileAnalysis analysis) {
        return new CompileResult(analysis, setup, hasModified);
    }
    public CompileResult withSetup(xsbti.compile.MiniSetup setup) {
        return new CompileResult(analysis, setup, hasModified);
    }
    public CompileResult withHasModified(boolean hasModified) {
        return new CompileResult(analysis, setup, hasModified);
    }
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        } else if (!(obj instanceof CompileResult)) {
            return false;
        } else {
            CompileResult o = (CompileResult)obj;
            return this.analysis().equals(o.analysis()) && this.setup().equals(o.setup()) && (this.hasModified() == o.hasModified());
        }
    }
    public int hashCode() {
        return 37 * (37 * (37 * (37 * (17 + "xsbti.compile.CompileResult".hashCode()) + analysis().hashCode()) + setup().hashCode()) + Boolean.valueOf(hasModified()).hashCode());
    }
    public String toString() {
        return "CompileResult("  + "analysis: " + analysis() + ", " + "setup: " + setup() + ", " + "hasModified: " + hasModified() + ")";
    }
}
