package droidmap

import org.jetbrains.kotlin.com.intellij.psi.JavaRecursiveElementVisitor
import org.jetbrains.kotlin.com.intellij.psi.PsiAnnotation
import org.jetbrains.kotlin.com.intellij.psi.PsiAnonymousClass
import org.jetbrains.kotlin.com.intellij.psi.PsiArrayType
import org.jetbrains.kotlin.com.intellij.psi.PsiAssignmentExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiExpressionList
import org.jetbrains.kotlin.com.intellij.psi.PsiExpressionStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiField
import org.jetbrains.kotlin.com.intellij.psi.PsiImportList
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiLocalVariable
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier
import org.jetbrains.kotlin.com.intellij.psi.PsiModifierList
import org.jetbrains.kotlin.com.intellij.psi.PsiNewExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiPackageStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiParameterList
import org.jetbrains.kotlin.com.intellij.psi.PsiPostfixExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiPrefixExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiPrimitiveType
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiType
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeElement
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeParameter
import org.jetbrains.kotlin.com.intellij.psi.PsiVariable
import org.jetbrains.kotlin.com.intellij.psi.PsiWildcardType
import org.jetbrains.kotlin.com.intellij.psi.impl.source.PsiClassReferenceType
import java.util.IdentityHashMap

/** Reads a Java file into the model in one walk. Types get their Kotlin names, so the extractors see one language. */
object JavaReader {
    private val PRIMITIVES = mapOf("int" to "Int", "long" to "Long", "short" to "Short", "byte" to "Byte", "boolean" to "Boolean",
        "double" to "Double", "float" to "Float", "char" to "Char")
    private val BOXED = mapOf("Integer" to "Int", "Character" to "Char")
    private val NON_NULL = setOf("NonNull", "NotNull", "Nonnull")

    fun read(path: String, module: String?, sourceSet: String?, text: String): Source {
        val file = Parser.java(path.substringAfterLast('/'), text)
        val imports = LinkedHashMap<String, String>()
        val star = ArrayList<String>()
        file.importList?.let { list ->
            for (i in list.importStatements) {
                val fq = i.importReference?.text?.filterNot { it.isWhitespace() } ?: continue
                if (i.isOnDemand) star.add(fq) else imports[fq.substringAfterLast('.')] = fq
            }
            for (i in list.importStaticStatements) {
                val fq = i.importReference?.text?.filterNot { it.isWhitespace() } ?: continue
                if (i.isOnDemand) star.add(fq) else imports[fq.substringAfterLast('.')] = fq
            }
        }
        val src = Source(path, module, sourceSet, file, file.text, file.packageName, imports, star)
        file.accept(Walker(src))
        return src
    }

    fun annotation(a: PsiAnnotation) = Ann(a.nameReferenceElement?.referenceName ?: "",
        a.parameterList.attributes.map { Arg(it.name, it.value, it) }, a.parameterList.text.ifEmpty { null }, a)

    fun anns(list: PsiModifierList?): List<Ann> = list?.annotations?.map { annotation(it) } ?: emptyList()

    /** The type of a declaration; a reference type is nullable unless the declaration says @NonNull. */
    fun typeRef(te: PsiTypeElement?, mods: PsiModifierList?): TypeRef? {
        if (te == null || te.isInferredType) return null
        val t = typeOf(te.type) ?: return null
        val nonNull = mods?.annotations?.any { it.nameReferenceElement?.referenceName in NON_NULL } == true
        return if (t.nullable && nonNull) t.copy(nullable = false) else t
    }

    private fun typeOf(t: PsiType): TypeRef? = when (t) {
        is PsiPrimitiveType -> PRIMITIVES[t.name]?.let { TypeRef(it) } // void is not a type here
        is PsiArrayType -> if ((t.componentType as? PsiPrimitiveType)?.name == "byte") TypeRef("ByteArray", nullable = true)
            else typeOf(t.componentType)?.let { TypeRef("Array", listOf(it), nullable = true) }
        is PsiClassReferenceType -> refType(t.reference)
        is PsiClassType -> TypeRef(t.className, t.parameters.mapNotNull { typeOf(it) }, nullable = true)
        is PsiWildcardType -> t.bound?.let { typeOf(it) }
        else -> null
    }

    /** The class type a reference names, such as java.util.List<Item>. */
    private fun refType(ref: PsiJavaCodeReferenceElement): TypeRef {
        val name = refName(ref).removePrefix("java.lang.")
        return TypeRef(BOXED[name] ?: name, ref.parameterList?.typeParameterElements?.mapNotNull { typeOf(it.type) } ?: emptyList(), nullable = true)
    }

    /** `a.b.C` for the reference a.b.C<X>, as written. */
    private fun refName(ref: PsiJavaCodeReferenceElement): String {
        val parts = ArrayList<String>()
        var r: PsiElement? = ref
        while (r is PsiJavaCodeReferenceElement) { parts.add(r.referenceName ?: "?"); r = r.qualifier }
        return parts.reversed().joinToString(".")
    }

    private fun args(list: PsiExpressionList?): List<Arg> = list?.expressions?.map { Arg(null, it, it) } ?: emptyList()

    private class Walker(private val src: Source) : JavaRecursiveElementVisitor() {
        private val typeByPsi = IdentityHashMap<PsiClass, Type>()
        private val fieldByPsi = IdentityHashMap<PsiVariable, Field>()
        private val fns = ArrayList<Fn>() // the methods around the current element, innermost last
        private val assigned = ArrayList<HashSet<String>>() // the local names each of those methods reassigns

        override fun visitImportList(list: PsiImportList) {}
        override fun visitPackageStatement(statement: PsiPackageStatement) {}

        override fun visitClass(c: PsiClass) {
            val fq = c.qualifiedName
            if (c !is PsiAnonymousClass && c !is PsiTypeParameter && fq != null) declare(c, fq)
            super.visitClass(c)
        }

        private fun declare(c: PsiClass, fq: String) {
            val kind = when {
                c.isAnnotationType -> TypeKind.ANNOTATION
                c.isInterface -> TypeKind.INTERFACE
                c.isEnum -> TypeKind.ENUM
                else -> TypeKind.CLASS
            }
            val supers = ArrayList<Super>()
            for (ref in c.extendsList?.referenceElements ?: emptyArray()) {
                // A class's superclass gets the arguments of its constructor's super(...) call; an interface extends interfaces.
                supers.add(Super(refType(ref), if (kind == TypeKind.CLASS) superCallArgs(c) else null, ref))
            }
            for (ref in c.implementsList?.referenceElements ?: emptyArray()) supers.add(Super(refType(ref), null, ref))
            val t = Type(c.name, fq, kind, anns(c.modifierList), supers, c.typeParameters.mapNotNull { it.name }, src, c,
                c.containingClass?.let { typeByPsi[it] })
            typeByPsi[c] = t
            src.types.add(t)
        }

        private fun superCallArgs(c: PsiClass): List<Arg> {
            val first = c.constructors.firstOrNull()?.body?.statements?.firstOrNull() as? PsiExpressionStatement
            val call = first?.expression as? PsiMethodCallExpression
            return if (call?.methodExpression?.text == "super") args(call.argumentList) else emptyList()
        }

        override fun visitMethod(m: PsiMethod) {
            val owner = m.containingClass?.let { typeByPsi[it] }
            val params = params(m.parameterList)
            if (m.isConstructor) {
                owner?.ctors?.add(Fn(m.name, null, null, params, anns(m.modifierList), src, m, owner))
                super.visitMethod(m)
                return
            }
            val fn = Fn(m.name, owner?.let { "${it.fq}.${m.name}" }, typeRef(m.returnTypeElement, m.modifierList), params, anns(m.modifierList), src, m, owner)
            src.fns.add(fn)
            owner?.fns?.add(fn)
            fns.add(fn)
            assigned.add(HashSet())
            super.visitMethod(m)
            val reassigned = assigned.removeLast()
            for (l in fn.locals) if (l.name !in reassigned) l.isConstant = true // effectively final
            fns.removeLast()
        }

        override fun visitField(f: PsiField) {
            val owner = f.containingClass?.let { typeByPsi[it] }
            val static = f.hasModifierProperty(PsiModifier.STATIC)
            val field = Field(f.name, owner?.let { "${it.fq}.${f.name}" }, typeRef(f.typeElement, f.modifierList), anns(f.modifierList), src, f, owner,
                f.initializer, null, isConstant = f.hasModifierProperty(PsiModifier.FINAL),
                stored = !static && !f.hasModifierProperty(PsiModifier.TRANSIENT), isStatic = static)
            fieldByPsi[f] = field
            src.fields.add(field)
            owner?.fields?.add(field)
            super.visitField(f)
        }

        override fun visitLocalVariable(v: PsiLocalVariable) {
            val field = Field(v.name, null, typeRef(v.typeElement, v.modifierList), anns(v.modifierList), src, v, null, v.initializer, null,
                isConstant = v.hasModifierProperty(PsiModifier.FINAL), stored = false, isLocal = true)
            fieldByPsi[v] = field
            src.fields.add(field)
            fns.lastOrNull()?.locals?.add(field)
            super.visitLocalVariable(v)
        }

        private fun reassigns(e: PsiExpression?) {
            (e as? PsiReferenceExpression)?.takeIf { it.qualifierExpression == null }?.referenceName?.let { assigned.lastOrNull()?.add(it) }
        }

        override fun visitAssignmentExpression(expression: PsiAssignmentExpression) {
            reassigns(expression.lExpression)
            super.visitAssignmentExpression(expression)
        }

        override fun visitPrefixExpression(expression: PsiPrefixExpression) {
            reassigns(expression.operand)
            super.visitPrefixExpression(expression)
        }

        override fun visitPostfixExpression(expression: PsiPostfixExpression) {
            reassigns(expression.operand)
            super.visitPostfixExpression(expression)
        }

        override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
            val me = expression.methodExpression
            val name = me.referenceName
            if (name != null && name != "super" && name != "this") {
                src.calls.add(Call(name, me.qualifierExpression?.text?.filterNot { it.isWhitespace() }, args(expression.argumentList),
                    me.parameterList?.typeParameterElements?.mapNotNull { typeRef(it, null) } ?: emptyList(), null,
                    src, expression, fns.lastOrNull(), assignee(expression)))
            }
            super.visitMethodCallExpression(expression)
        }

        override fun visitNewExpression(expression: PsiNewExpression) {
            val ref = expression.classOrAnonymousClassReference
            if (ref != null) {
                val t = refName(ref)
                src.calls.add(Call(t.substringAfterLast('.'), t.substringBeforeLast('.', "").ifEmpty { null }, args(expression.argumentList),
                    emptyList(), null, src, expression, fns.lastOrNull(), assignee(expression), isNew = true))
            }
            super.visitNewExpression(expression)
        }

        /** The field or variable that [e] initializes. */
        private fun assignee(e: PsiExpression): Field? = (e.parent as? PsiVariable)?.takeIf { it.initializer == e }?.let { fieldByPsi[it] }

        override fun visitReferenceElement(reference: PsiJavaCodeReferenceElement) {
            reference.referenceName?.let { src.names.merge(it, 1, Int::plus) }
            super.visitReferenceElement(reference)
        }

        override fun visitReferenceExpression(expression: PsiReferenceExpression) {
            val q = expression.qualifierExpression
            if (q != null && isRId(q.text)) expression.referenceName?.let { src.resourceIds.add(it) }
            super.visitReferenceExpression(expression)
        }

        private fun params(list: PsiParameterList) = list.parameters.map { Param(it.name, typeRef(it.typeElement, it.modifierList), anns(it.modifierList), src, it) }
    }
}
