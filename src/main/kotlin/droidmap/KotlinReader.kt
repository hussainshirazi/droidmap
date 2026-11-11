package droidmap

import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtImportList
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyDelegate
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeElement
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import java.util.IdentityHashMap

/** Reads a Kotlin file into the model in one walk. */
object KotlinReader {
    fun read(path: String, module: String?, sourceSet: String?, text: String): Source {
        val file = Parser.kotlin(path.substringAfterLast('/'), text)
        val imports = LinkedHashMap<String, String>()
        val star = ArrayList<String>()
        for (d in file.importDirectives) {
            val fq = d.importedFqName?.asString() ?: continue
            if (d.isAllUnder) star.add(fq) else imports[d.aliasName ?: fq.substringAfterLast('.')] = fq
        }
        val src = Source(path, module, sourceSet, file, file.text, file.packageFqName.asString(), imports, star)
        file.accept(Walker(src))
        return src
    }

    fun typeRef(ref: KtTypeReference?): TypeRef? = ref?.typeElement?.let { typeRef(it) }

    private fun typeRef(el: KtTypeElement): TypeRef? = when (el) {
        is KtNullableType -> el.innerType?.let { typeRef(it) }?.copy(nullable = true)
        is KtFunctionType -> TypeRef("Function", fn = true)
        is KtUserType -> {
            val names = ArrayList<String>()
            var u: KtUserType? = el
            while (u != null) { names.add(u.referencedName ?: "?"); u = u.qualifier }
            TypeRef(names.reversed().joinToString("."), el.typeArgumentsAsTypes.mapNotNull { typeRef(it) })
        }
        else -> null
    }

    fun args(list: KtValueArgumentList?): List<Arg> =
        list?.arguments?.map { Arg(it.getArgumentName()?.asName?.asString(), it.getArgumentExpression(), it) } ?: emptyList()

    fun anns(a: KtAnnotated): List<Ann> =
        a.annotationEntries.map { Ann(it.shortName?.asString() ?: "", args(it.valueArgumentList), it.valueArgumentList?.text, it) }

    private class Walker(private val src: Source) : KtTreeVisitorVoid() {
        private val typeByPsi = IdentityHashMap<KtClassOrObject, Type>()
        private val fieldByPsi = IdentityHashMap<KtProperty, Field>()
        private val fns = ArrayList<Fn>() // the named functions around the current element, innermost last

        // Names in imports and the package line are not uses.
        override fun visitImportList(importList: KtImportList) {}
        override fun visitPackageDirective(directive: KtPackageDirective) {}

        override fun visitClassOrObject(c: KtClassOrObject) {
            val fq = c.fqName?.asString()
            if (c is KtObjectDeclaration && c.isObjectLiteral()) objectLiteral(c)
            else if (fq != null) declare(c, fq) // (a local class has no fq name and is not a type of the model)
            super.visitClassOrObject(c)
        }

        private fun declare(c: KtClassOrObject, fq: String) {
            val kind = when {
                c is KtObjectDeclaration -> if (c.isCompanion()) TypeKind.COMPANION else TypeKind.OBJECT
                c is KtClass && c.isInterface() -> TypeKind.INTERFACE
                c is KtClass && c.isAnnotation() -> TypeKind.ANNOTATION
                c is KtClass && c.isEnum() -> TypeKind.ENUM
                else -> TypeKind.CLASS
            }
            val supers = c.superTypeListEntries.mapNotNull { e ->
                typeRef(e.typeReference)?.let { Super(it, (e as? KtSuperTypeCallEntry)?.let { call -> args(call.valueArgumentList) }, e) }
            }
            val t = Type(c.name, fq, kind, anns(c), supers, c.typeParameters.mapNotNull { it.name }, src, c,
                c.containingClassOrObject?.let { typeByPsi[it] }, c.hasModifier(KtTokens.EXPECT_KEYWORD))
            typeByPsi[c] = t
            src.types.add(t)
            c.primaryConstructor?.let { t.ctors.add(Fn(c.name, null, null, params(it.valueParameters), anns(it), src, it, t)) }
            if (c is KtClass) for (sc in c.secondaryConstructors) t.ctors.add(Fn(c.name, null, null, params(sc.valueParameters), anns(sc), src, sc, t))
            for (prm in c.primaryConstructorParameters) {
                if (!prm.hasValOrVar()) continue
                t.fields.add(Field(prm.name, "$fq.${prm.name}", typeRef(prm.typeReference), anns(prm), src, prm, t, null, null,
                    isConstant = !prm.isMutable, stored = true, isParam = true))
            }
        }

        /** `object : Migration(1, 2) { }` is read as a call to its superclass's constructor. */
        private fun objectLiteral(c: KtObjectDeclaration) {
            val lit = c.parent as? KtObjectLiteralExpression ?: return
            for (e in c.superTypeListEntries) {
                if (e !is KtSuperTypeCallEntry) continue
                val t = typeRef(e.typeReference) ?: continue
                src.calls.add(Call(t.simple, t.name.substringBeforeLast('.', "").ifEmpty { null }, args(e.valueArgumentList), emptyList(), null,
                    src, e, fns.lastOrNull(), assignee(lit), isNew = true))
            }
        }

        override fun visitNamedFunction(f: KtNamedFunction) {
            val owner = f.containingClassOrObject?.let { typeByPsi[it] } // null for top-level, local and anonymous-object functions
            val fn = Fn(f.name, f.fqName?.asString(), typeRef(f.typeReference), params(f.valueParameters), anns(f), src, f, owner,
                f.hasModifier(KtTokens.EXPECT_KEYWORD))
            src.fns.add(fn)
            owner?.fns?.add(fn)
            fns.add(fn)
            super.visitNamedFunction(f)
            fns.removeLast()
        }

        override fun visitProperty(p: KtProperty) {
            val owner = p.containingClassOrObject?.let { typeByPsi[it] }
            val stored = !p.hasDelegate() && !p.hasModifier(KtTokens.ABSTRACT_KEYWORD) &&
                (p.initializer != null || p.hasModifier(KtTokens.LATEINIT_KEYWORD) || p.getter == null)
            val field = Field(p.name, p.fqName?.asString(), typeRef(p.typeReference), anns(p), src, p, owner, p.initializer, p.delegateExpression,
                isConstant = !p.isVar, stored = stored, isLocal = p.isLocal, isExpect = p.hasModifier(KtTokens.EXPECT_KEYWORD))
            fieldByPsi[p] = field
            src.fields.add(field)
            owner?.fields?.add(field)
            if (p.isLocal) fns.lastOrNull()?.locals?.add(field)
            super.visitProperty(p)
        }

        override fun visitCallExpression(call: KtCallExpression) {
            val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
            if (name != null) {
                val q = (call.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == call }
                src.calls.add(Call(name, q?.receiverExpression?.text, args(call.valueArgumentList),
                    call.typeArguments.mapNotNull { typeRef(it.typeReference) }, call.lambdaArguments.lastOrNull()?.getLambdaExpression(),
                    src, call, fns.lastOrNull(), assignee(q ?: call)))
            }
            super.visitCallExpression(call)
        }

        /** The property that [e] initializes or (with `by`) delegates. */
        private fun assignee(e: KtExpression): Field? = when (val p = e.parent) {
            is KtProperty -> if (p.initializer == e) fieldByPsi[p] else null
            is KtPropertyDelegate -> (p.parent as? KtProperty)?.let { fieldByPsi[it] }
            else -> null
        }

        override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
            if (expression is KtNameReferenceExpression) src.names.merge(expression.getReferencedName(), 1, Int::plus)
            super.visitSimpleNameExpression(expression)
        }

        override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
            val key = (expression.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
            if (key != null && isRId(expression.receiverExpression.text)) src.resourceIds.add(key)
            super.visitDotQualifiedExpression(expression)
        }

        private fun params(ps: List<KtParameter>) = ps.map { Param(it.name, typeRef(it.typeReference), anns(it), src, it) }
    }
}

/** Whether [receiver] is `R.id` (or a qualified `com.example.R.id`). */
fun isRId(receiver: String): Boolean {
    val r = receiver.filterNot { it.isWhitespace() }
    return r == "R.id" || r.endsWith(".R.id")
}
