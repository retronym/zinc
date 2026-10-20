/**
 * This code is generated using [[https://www.scala-sbt.org/contraband]].
 */

// DO NOT EDIT MANUALLY
package xsbti.api;
public final class AnalyzedClass implements java.io.Serializable {
    
    public static AnalyzedClass create(long _compilationTimestamp, String _name, xsbti.api.Lazy<Companions> _api, int _apiHash, NameHash[] _nameHashes, boolean _hasMacro) {
        return new AnalyzedClass(_compilationTimestamp, _name, _api, _apiHash, _nameHashes, _hasMacro);
    }
    public static AnalyzedClass of(long _compilationTimestamp, String _name, xsbti.api.Lazy<Companions> _api, int _apiHash, NameHash[] _nameHashes, boolean _hasMacro) {
        return new AnalyzedClass(_compilationTimestamp, _name, _api, _apiHash, _nameHashes, _hasMacro);
    }
    public static AnalyzedClass create(long _compilationTimestamp, String _name, xsbti.api.Lazy<Companions> _api, int _apiHash, NameHash[] _nameHashes, boolean _hasMacro, int _extraHash) {
        return new AnalyzedClass(_compilationTimestamp, _name, _api, _apiHash, _nameHashes, _hasMacro, _extraHash);
    }
    public static AnalyzedClass of(long _compilationTimestamp, String _name, xsbti.api.Lazy<Companions> _api, int _apiHash, NameHash[] _nameHashes, boolean _hasMacro, int _extraHash) {
        return new AnalyzedClass(_compilationTimestamp, _name, _api, _apiHash, _nameHashes, _hasMacro, _extraHash);
    }
    public static AnalyzedClass create(long _compilationTimestamp, String _name, xsbti.api.Lazy<Companions> _api, int _apiHash, NameHash[] _nameHashes, boolean _hasMacro, int _extraHash, String _provenance) {
        return new AnalyzedClass(_compilationTimestamp, _name, _api, _apiHash, _nameHashes, _hasMacro, _extraHash, _provenance);
    }
    public static AnalyzedClass of(long _compilationTimestamp, String _name, xsbti.api.Lazy<Companions> _api, int _apiHash, NameHash[] _nameHashes, boolean _hasMacro, int _extraHash, String _provenance) {
        return new AnalyzedClass(_compilationTimestamp, _name, _api, _apiHash, _nameHashes, _hasMacro, _extraHash, _provenance);
    }
    public static AnalyzedClass create(long _compilationTimestamp, String _name, xsbti.api.Lazy<Companions> _api, int _apiHash, NameHash[] _nameHashes, boolean _hasMacro, int _extraHash, String _provenance, String[] _ancestors, String[] _moduleAncestors) {
        return new AnalyzedClass(_compilationTimestamp, _name, _api, _apiHash, _nameHashes, _hasMacro, _extraHash, _provenance, _ancestors, _moduleAncestors);
    }
    public static AnalyzedClass of(long _compilationTimestamp, String _name, xsbti.api.Lazy<Companions> _api, int _apiHash, NameHash[] _nameHashes, boolean _hasMacro, int _extraHash, String _provenance, String[] _ancestors, String[] _moduleAncestors) {
        return new AnalyzedClass(_compilationTimestamp, _name, _api, _apiHash, _nameHashes, _hasMacro, _extraHash, _provenance, _ancestors, _moduleAncestors);
    }
    private long compilationTimestamp;
    private String name;
    private xsbti.api.Lazy<Companions> api;
    private int apiHash;
    private NameHash[] nameHashes;
    private boolean hasMacro;
    private int extraHash;
    private String provenance;
    private String[] ancestors;
    private String[] moduleAncestors;
    protected AnalyzedClass(long _compilationTimestamp, String _name, xsbti.api.Lazy<Companions> _api, int _apiHash, NameHash[] _nameHashes, boolean _hasMacro) {
        super();
        compilationTimestamp = _compilationTimestamp;
        name = _name;
        api = _api;
        apiHash = _apiHash;
        nameHashes = _nameHashes;
        hasMacro = _hasMacro;
        extraHash = apiHash;
        provenance = "";
        ancestors = new String[0];
        moduleAncestors = new String[0];
    }
    protected AnalyzedClass(long _compilationTimestamp, String _name, xsbti.api.Lazy<Companions> _api, int _apiHash, NameHash[] _nameHashes, boolean _hasMacro, int _extraHash) {
        super();
        compilationTimestamp = _compilationTimestamp;
        name = _name;
        api = _api;
        apiHash = _apiHash;
        nameHashes = _nameHashes;
        hasMacro = _hasMacro;
        extraHash = _extraHash;
        provenance = "";
        ancestors = new String[0];
        moduleAncestors = new String[0];
    }
    protected AnalyzedClass(long _compilationTimestamp, String _name, xsbti.api.Lazy<Companions> _api, int _apiHash, NameHash[] _nameHashes, boolean _hasMacro, int _extraHash, String _provenance) {
        super();
        compilationTimestamp = _compilationTimestamp;
        name = _name;
        api = _api;
        apiHash = _apiHash;
        nameHashes = _nameHashes;
        hasMacro = _hasMacro;
        extraHash = _extraHash;
        provenance = _provenance;
        ancestors = new String[0];
        moduleAncestors = new String[0];
    }
    protected AnalyzedClass(long _compilationTimestamp, String _name, xsbti.api.Lazy<Companions> _api, int _apiHash, NameHash[] _nameHashes, boolean _hasMacro, int _extraHash, String _provenance, String[] _ancestors, String[] _moduleAncestors) {
        super();
        compilationTimestamp = _compilationTimestamp;
        name = _name;
        api = _api;
        apiHash = _apiHash;
        nameHashes = _nameHashes;
        hasMacro = _hasMacro;
        extraHash = _extraHash;
        provenance = _provenance;
        ancestors = _ancestors;
        moduleAncestors = _moduleAncestors;
    }
    
    public long compilationTimestamp() {
        return this.compilationTimestamp;
    }
    public String name() {
        return this.name;
    }
    public Companions api() {
        return this.api.get();
    }
    public int apiHash() {
        return this.apiHash;
    }
    public NameHash[] nameHashes() {
        return this.nameHashes;
    }
    public boolean hasMacro() {
        return this.hasMacro;
    }
    public int extraHash() {
        return this.extraHash;
    }
    /**
     * An identifier of the 'provenance' of a class, such as the jar that contained it.
     * Combined with a way to tell if the provenance has changed,
     * it can be used to short-circuit the 'lookupAnalyzedClass' operation.
     */
    public String provenance() {
        return this.provenance;
    }
    /**
     * Under Merkle hashing, the binary names of the class side's base classes in
     * linearization order, without the class itself; empty if it has no class side.
     */
    public String[] ancestors() {
        return this.ancestors;
    }
    /**
     * Under Merkle hashing, the binary names of the object side's base classes in
     * linearization order, without the object itself; empty if it has no object side.
     */
    public String[] moduleAncestors() {
        return this.moduleAncestors;
    }
    public AnalyzedClass withCompilationTimestamp(long compilationTimestamp) {
        return new AnalyzedClass(compilationTimestamp, name, api, apiHash, nameHashes, hasMacro, extraHash, provenance, ancestors, moduleAncestors);
    }
    public AnalyzedClass withName(String name) {
        return new AnalyzedClass(compilationTimestamp, name, api, apiHash, nameHashes, hasMacro, extraHash, provenance, ancestors, moduleAncestors);
    }
    public AnalyzedClass withApi(xsbti.api.Lazy<Companions> api) {
        return new AnalyzedClass(compilationTimestamp, name, api, apiHash, nameHashes, hasMacro, extraHash, provenance, ancestors, moduleAncestors);
    }
    public AnalyzedClass withApiHash(int apiHash) {
        return new AnalyzedClass(compilationTimestamp, name, api, apiHash, nameHashes, hasMacro, extraHash, provenance, ancestors, moduleAncestors);
    }
    public AnalyzedClass withNameHashes(NameHash[] nameHashes) {
        return new AnalyzedClass(compilationTimestamp, name, api, apiHash, nameHashes, hasMacro, extraHash, provenance, ancestors, moduleAncestors);
    }
    public AnalyzedClass withHasMacro(boolean hasMacro) {
        return new AnalyzedClass(compilationTimestamp, name, api, apiHash, nameHashes, hasMacro, extraHash, provenance, ancestors, moduleAncestors);
    }
    public AnalyzedClass withExtraHash(int extraHash) {
        return new AnalyzedClass(compilationTimestamp, name, api, apiHash, nameHashes, hasMacro, extraHash, provenance, ancestors, moduleAncestors);
    }
    public AnalyzedClass withProvenance(String provenance) {
        return new AnalyzedClass(compilationTimestamp, name, api, apiHash, nameHashes, hasMacro, extraHash, provenance, ancestors, moduleAncestors);
    }
    public AnalyzedClass withAncestors(String[] ancestors) {
        return new AnalyzedClass(compilationTimestamp, name, api, apiHash, nameHashes, hasMacro, extraHash, provenance, ancestors, moduleAncestors);
    }
    public AnalyzedClass withModuleAncestors(String[] moduleAncestors) {
        return new AnalyzedClass(compilationTimestamp, name, api, apiHash, nameHashes, hasMacro, extraHash, provenance, ancestors, moduleAncestors);
    }
    public boolean equals(Object obj) {
        return this == obj; // We have lazy members, so use object identity to avoid circularity.
    }
    public int hashCode() {
        return super.hashCode(); // Avoid evaluating lazy members in hashCode to avoid circularity.
    }
    public String toString() {
        return super.toString(); // Avoid evaluating lazy members in toString to avoid circularity.
    }
}
