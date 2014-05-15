package org.benf.cfr.reader.bytecode.analysis.types;

import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;
import org.benf.cfr.reader.entities.ClassFile;
import org.benf.cfr.reader.state.DCCommonState;
import org.benf.cfr.reader.state.TypeUsageCollector;
import org.benf.cfr.reader.state.TypeUsageInformation;
import org.benf.cfr.reader.util.CannotLoadClassException;
import org.benf.cfr.reader.util.MapFactory;
import org.benf.cfr.reader.util.MiscConstants;
import org.benf.cfr.reader.util.annotation.Nullable;
import org.benf.cfr.reader.util.output.Dumper;
import org.benf.cfr.reader.util.output.ToStringDumper;

import java.util.Map;

public class JavaRefTypeInstance implements JavaTypeInstance {
    private final String className;
    private transient String shortName; // may not be unique
    private transient String suggestedVarName;
    private transient InnerClassInfo innerClassInfo; // info about this class AS AN INNER CLASS.
    //    private final Options options;
    private final DCCommonState dcCommonState; // Shouldn't need this here...
    private BindingSuperContainer cachedBindingSupers = BindingSuperContainer.POISON;

    private JavaRefTypeInstance(final String className, DCCommonState dcCommonState) {
        this.innerClassInfo = InnerClassInfo.NOT;
        this.dcCommonState = dcCommonState;
        /*
         * This /MAY/ be a red herring.  It's possible for a class name to contain a dollar
         * without it being an inner class.
         */
        if (className.contains(MiscConstants.INNER_CLASS_SEP_STR)) {
            String outer = className.substring(0, className.lastIndexOf(MiscConstants.INNER_CLASS_SEP_CHAR));
            JavaRefTypeInstance outerClassTmp = dcCommonState.getClassCache().getRefClassFor(outer);
            innerClassInfo = new RefTypeInnerClassInfo(outerClassTmp);
        }
        this.className = className;
        this.shortName = getShortName(className, innerClassInfo);
    }

    private JavaRefTypeInstance(final String className, final JavaRefTypeInstance knownOuter, DCCommonState dcCommonState) {
        this.className = className;
        this.dcCommonState = dcCommonState;
        String innerSub = className.substring(knownOuter.className.length());
        /*
         * Now, we have to make an assumption at this point that if the first character of innerSub is a $ (Sep)
         * that we should replace it.  This isn't mandated by anything, so we could create illegals again here. :P
         *
         */
        if (innerSub.charAt(0) == MiscConstants.INNER_CLASS_SEP_CHAR) {
            innerSub = innerSub.substring(1);
        }
        this.innerClassInfo = new RefTypeInnerClassInfo(knownOuter);
        this.shortName = innerSub;
    }

    public void markNotInner() {
        this.innerClassInfo = InnerClassInfo.NOT;
        this.shortName = getShortName(className, innerClassInfo);
    }

    @Override
    public String suggestVarName() {
        if (suggestedVarName != null) return suggestedVarName;

        String displayName = this.shortName;
        if (displayName.isEmpty()) return null;
        char[] chars = displayName.toCharArray();
        int x = 0;
        int len = chars.length;
        for (x = 0; x < len; ++x) {
            char c = chars[x];
            if (c >= '0' && c <= '9') continue;
            break;
        }
        if (x >= len) return null;
        chars[x] = Character.toLowerCase(chars[x]);
        displayName = new String(chars, x, len - x);
        return displayName;
    }

    private JavaRefTypeInstance(String className, String displayableName, JavaRefTypeInstance[] supers) {
        this.innerClassInfo = InnerClassInfo.NOT;
        this.dcCommonState = null; // TODO : Wrong.
        this.className = className;
        this.shortName = displayableName;
        Map<JavaRefTypeInstance, JavaGenericRefTypeInstance> tmp = MapFactory.newMap();
        Map<JavaRefTypeInstance, BindingSuperContainer.Route> routes = MapFactory.newMap();
        for (JavaRefTypeInstance supr : supers) {
            tmp.put(supr, null);
            routes.put(supr, BindingSuperContainer.Route.EXTENSION);
        }

        this.cachedBindingSupers = new BindingSuperContainer(null, tmp, routes);
    }

    /*
     * Only call from constPool cache.
     */
    public static JavaRefTypeInstance create(String rawClassName, DCCommonState dcCommonState) {
        return new JavaRefTypeInstance(rawClassName, dcCommonState);
    }

    public static Pair<JavaRefTypeInstance, JavaRefTypeInstance> createKnownInnerOuter(String inner, String outer, JavaRefTypeInstance outerType, DCCommonState dcCommonState) {
        if (outerType == null) outerType = new JavaRefTypeInstance(outer, dcCommonState);
        JavaRefTypeInstance innerType;
        if (!inner.startsWith(outer)) {
            // Handle the illegal inner/outer combo.
            innerType = new JavaRefTypeInstance(inner, dcCommonState);
        } else {
            innerType = new JavaRefTypeInstance(inner, outerType, dcCommonState);
        }
        return Pair.make(innerType, outerType);
    }

    /*
     * ONLY call from TypeConstants.
     */
    public static JavaRefTypeInstance createTypeConstant(String rawClassName, String displayableName, JavaRefTypeInstance... supers) {
        return new JavaRefTypeInstance(rawClassName, displayableName, supers);
    }

    @Override
    public StackType getStackType() {
        return StackType.REF;
    }

    @Override
    public void dumpInto(Dumper d, TypeUsageInformation typeUsageInformation) {
        String res = typeUsageInformation.getName(this);
        if (res == null) throw new IllegalStateException();
        d.print(res);
    }

    @Override
    public String toString() {
        return new ToStringDumper().dump(this).toString();
    }

    @Override
    public JavaTypeInstance getArrayStrippedType() {
        return this;
    }

    @Override
    public int getNumArrayDimensions() {
        return 0;
    }

    @Override
    public String getRawName() {
        return className;
    }

    public String getRawShortName() {
        return shortName;
    }

    @Override
    public int hashCode() {
        return 31 + className.hashCode();
    }

    @Override
    public InnerClassInfo getInnerClassHereInfo() {
        return innerClassInfo;
    }

    @Override
    public BindingSuperContainer getBindingSupers() {
        if (cachedBindingSupers != BindingSuperContainer.POISON) return cachedBindingSupers;
        try {
            ClassFile classFile = getClassFile();
            cachedBindingSupers = classFile == null ? null : classFile.getBindingSupers();
        } catch (CannotLoadClassException e) {
            cachedBindingSupers = null;
        }
        return cachedBindingSupers;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof JavaRefTypeInstance)) return false;
        JavaRefTypeInstance other = (JavaRefTypeInstance) o;
        return other.className.equals(className);
    }

    @Override
    public boolean isComplexType() {
        return true;
    }

    @Override
    public boolean isUsableType() {
        return true;
    }

    @Override
    public JavaTypeInstance removeAnArrayIndirection() {
        /*
         * We could end up with this if we have accidental false slot sharing.
         * Don't fail because of it.
         */
        return this;
    }

    @Override
    public JavaTypeInstance getDeGenerifiedType() {
        return this;
    }

    @Override
    public RawJavaType getRawTypeOfSimpleType() {
        return RawJavaType.REF;
    }

    @Override
    public boolean implicitlyCastsTo(JavaTypeInstance other, @Nullable GenericTypeBinder gtb) {
        if (this.equals(other)) return true;
        if (other instanceof RawJavaType) {
            /*
             * If this is boxed, we can unbox, and cast up.
             */
            RawJavaType thisAsRaw = RawJavaType.getUnboxedTypeFor(this);
            if (thisAsRaw != null) {
                return thisAsRaw.implicitlyCastsTo(other, gtb);
            }
        }
        if (gtb != null && other instanceof JavaGenericPlaceholderTypeInstance) {
            other = gtb.getBindingFor(other);
            if (!(other instanceof JavaGenericPlaceholderTypeInstance)) {
                return implicitlyCastsTo(other, gtb);
            }
        }
        if (other instanceof JavaGenericPlaceholderTypeInstance) {
            return false;
        }
        JavaTypeInstance otherRaw = other.getDeGenerifiedType();
        BindingSuperContainer thisBindingSuper = this.getBindingSupers();
        if (thisBindingSuper == null) {
            return false;
        }
        return thisBindingSuper.containsBase(otherRaw);
    }

    /*
     * Fixme - shouldn't this use binding supercontainer?
     */
    @Override
    public boolean canCastTo(JavaTypeInstance other, GenericTypeBinder gtb) {
        if (other instanceof RawJavaType) {
            /*
             * If this is boxed, we can unbox, and cast up.
             */
            RawJavaType thisAsRaw = RawJavaType.getUnboxedTypeFor(this);
            if (thisAsRaw != null) {
                return thisAsRaw.equals(other);
            }
            return true;
        }

        return true;
    }

    public ClassFile getClassFile() {
        if (dcCommonState == null) return null;
        ClassFile classFile = dcCommonState.getClassFile(this);
        return classFile;
    }

    private static String getShortName(String fullClassName, InnerClassInfo innerClassInfo) {
        if (innerClassInfo.isInnerClass()) {
            fullClassName = fullClassName.replace(MiscConstants.INNER_CLASS_SEP_CHAR, '.');
        }
        int idxlast = fullClassName.lastIndexOf('.');
        String partname = idxlast == -1 ? fullClassName : fullClassName.substring(idxlast + 1);
        return partname;
    }

    @Override
    public void collectInto(TypeUsageCollector typeUsageCollector) {
        typeUsageCollector.collectRefType(this);
    }

    private static class RefTypeInnerClassInfo implements InnerClassInfo {
        private final JavaRefTypeInstance outerClass;
        private boolean isAnonymous = false;
        private boolean isMethodScoped = false;
        private boolean hideSyntheticThis = false;

        private RefTypeInnerClassInfo(JavaRefTypeInstance outerClass) {
            this.outerClass = outerClass;
        }

        @Override
        public boolean isInnerClass() {
            return true;
        }

        @Override
        public boolean isAnonymousClass() {
            return isAnonymous;
        }

        @Override
        public boolean isMethodScopedClass() {
            return isMethodScoped;
        }

        @Override
        public void markMethodScoped(boolean isAnonymous) {
            this.isAnonymous = isAnonymous;
            this.isMethodScoped = true;
        }

        @Override
        public boolean isInnerClassOf(JavaTypeInstance possibleParent) {
            if (outerClass == null) return false;
            return possibleParent.equals(outerClass);
        }

        @Override
        public boolean isTransitiveInnerClassOf(JavaTypeInstance possibleParent) {
            if (outerClass == null) return false;
            if (possibleParent.equals(outerClass)) return true;
            InnerClassInfo upper = outerClass.getInnerClassHereInfo();
            if (!upper.isInnerClass()) return false;
            return upper.isTransitiveInnerClassOf(possibleParent);
        }

        @Override
        public void setHideSyntheticThis() {
            hideSyntheticThis = true;
        }

        @Override
        public JavaRefTypeInstance getOuterClass() {
            return outerClass;
        }

        @Override
        public boolean isHideSyntheticThis() {
            return hideSyntheticThis;
        }
    }

}
