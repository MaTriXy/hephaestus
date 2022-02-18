package com.squareup.anvil.compiler.internal.reference

import com.squareup.anvil.annotations.ExperimentalAnvilApi
import com.squareup.anvil.compiler.api.AnvilCompilationException
import com.squareup.anvil.compiler.internal.annotationOrNull
import com.squareup.anvil.compiler.internal.asClassName
import com.squareup.anvil.compiler.internal.fqNameOrNull
import com.squareup.anvil.compiler.internal.functions
import com.squareup.anvil.compiler.internal.properties
import com.squareup.anvil.compiler.internal.reference.ClassReference.Descriptor
import com.squareup.anvil.compiler.internal.reference.ClassReference.Psi
import com.squareup.anvil.compiler.internal.reference.Visibility.INTERNAL
import com.squareup.anvil.compiler.internal.reference.Visibility.PRIVATE
import com.squareup.anvil.compiler.internal.reference.Visibility.PROTECTED
import com.squareup.anvil.compiler.internal.reference.Visibility.PUBLIC
import com.squareup.anvil.compiler.internal.requireContainingClassReference
import com.squareup.anvil.compiler.internal.requireFqName
import com.squareup.anvil.compiler.internal.scope
import com.squareup.anvil.compiler.internal.scopeOrNull
import com.squareup.anvil.compiler.internal.superTypeListEntryOrNull
import com.squareup.kotlinpoet.ClassName
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.Modality.ABSTRACT
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.lexer.KtTokens.ABSTRACT_KEYWORD
import org.jetbrains.kotlin.lexer.KtTokens.INTERNAL_KEYWORD
import org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD
import org.jetbrains.kotlin.lexer.KtTokens.PROTECTED_KEYWORD
import org.jetbrains.kotlin.lexer.KtTokens.PUBLIC_KEYWORD
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtTypeArgumentList
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.allConstructors
import org.jetbrains.kotlin.psi.psiUtil.getChildOfType
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.psi.psiUtil.visibilityModifierTypeOrDefault
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperClassNotAny
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperInterfaces
import org.jetbrains.kotlin.resolve.descriptorUtil.parents
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.getDescriptorsFiltered
import org.jetbrains.kotlin.types.DefinitelyNotNullType
import org.jetbrains.kotlin.types.FlexibleType
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.utils.addToStdlib.cast
import kotlin.LazyThreadSafetyMode.NONE

/**
 * Used to create a common type between [KtClassOrObject] class references and [ClassDescriptor]
 * references, to streamline parsing.
 *
 * @see toClassReference
 */
@ExperimentalAnvilApi
public sealed class ClassReference {

  public abstract val classId: ClassId
  public abstract val fqName: FqName
  public abstract val module: AnvilModuleDescriptor

  public val shortName: String get() = fqName.shortName().asString()
  public val packageFqName: FqName get() = classId.packageFqName

  public abstract val constructors: List<FunctionReference>
  public abstract val functions: List<FunctionReference>
  public abstract val annotations: List<AnnotationReference>
  public abstract val properties: List<PropertyReference>

  public abstract fun isInterface(): Boolean
  public abstract fun isAbstract(): Boolean
  public abstract fun isObject(): Boolean
  public abstract fun isCompanion(): Boolean
  public abstract fun visibility(): Visibility

  /**
   * Returns only the super class (excluding [Any]) and implemented interfaces declared directly by
   * this class.
   */
  public abstract fun directSuperClassReferences(): List<ClassReference>

  /**
   * Returns all outer classes including this class. Imagine the inner class `Outer.Middle.Inner`,
   * then the returned list would contain `[Outer, Middle, Inner]` in that order.
   */
  public abstract fun enclosingClassesWithSelf(): List<ClassReference>

  public fun enclosingClass(): ClassReference? {
    val classes = enclosingClassesWithSelf()
    val index = classes.indexOf(this)
    return if (index == 0) null else classes[index - 1]
  }

  public abstract fun innerClasses(): List<ClassReference>

  public open fun companionObjects(): List<ClassReference> {
    return innerClasses().filter { it.isCompanion() && it.enclosingClass() == this }
  }

  override fun toString(): String {
    return "${this::class.qualifiedName}($fqName)"
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ClassReference) return false

    if (fqName != other.fqName) return false

    return true
  }

  override fun hashCode(): Int {
    return fqName.hashCode()
  }

  public class Psi(
    public val clazz: KtClassOrObject,
    override val classId: ClassId,
    override val module: AnvilModuleDescriptor
  ) : ClassReference() {
    override val fqName: FqName = classId.asSingleFqName()

    override val constructors: List<FunctionReference.Psi> by lazy(NONE) {
      clazz.allConstructors.map { it.toFunctionReference(this) }
    }

    override val functions: List<FunctionReference.Psi> by lazy(NONE) {
      clazz
        .functions(includeCompanionObjects = false)
        .map { it.toFunctionReference(this) }
    }

    override val annotations: List<AnnotationReference.Psi> by lazy(NONE) {
      clazz.annotationEntries.map { it.toAnnotationReference(this, module) }
    }

    override val properties: List<PropertyReference.Psi> by lazy(NONE) {
      clazz
        .properties(includeCompanionObjects = false)
        .map { it.toPropertyReference(this) }
    }

    private val directSuperClassReferences: List<ClassReference> by lazy(NONE) {
      clazz.superTypeListEntries.map { it.requireFqName(module).toClassReference(module) }
    }

    private val enclosingClassesWithSelf by lazy(NONE) {
      clazz.parents
        .filterIsInstance<KtClassOrObject>()
        .map { it.toClassReference(module) }
        .toList()
        .reversed()
        .plus(this)
    }

    private val innerClasses by lazy(NONE) {
      generateSequence(clazz.declarations.filterIsInstance<KtClassOrObject>()) { classes ->
        classes
          .flatMap { it.declarations }
          .filterIsInstance<KtClassOrObject>()
          .ifEmpty { null }
      }
        .flatten()
        .map { it.toClassReference(module) }
        .toList()
    }

    override fun isInterface(): Boolean = clazz is KtClass && clazz.isInterface()

    override fun isAbstract(): Boolean = clazz.hasModifier(ABSTRACT_KEYWORD)

    override fun isObject(): Boolean = clazz is KtObjectDeclaration

    override fun isCompanion(): Boolean = clazz is KtObjectDeclaration && clazz.isCompanion()

    override fun visibility(): Visibility {
      return when (val visibility = clazz.visibilityModifierTypeOrDefault()) {
        PUBLIC_KEYWORD -> PUBLIC
        INTERNAL_KEYWORD -> INTERNAL
        PROTECTED_KEYWORD -> PROTECTED
        PRIVATE_KEYWORD -> PRIVATE
        else -> throw AnvilCompilationExceptionClassReference(
          classReference = this,
          message = "Couldn't get visibility $visibility for class $fqName."
        )
      }
    }

    override fun directSuperClassReferences(): List<ClassReference> = directSuperClassReferences

    override fun enclosingClassesWithSelf(): List<Psi> = enclosingClassesWithSelf

    override fun innerClasses(): List<Psi> = innerClasses

    override fun companionObjects(): List<Psi> {
      return super.companionObjects().cast()
    }

    /**
     * Safely resolves a [KotlinType], when that type reference is a generic expressed by a type
     * variable name. This is done by inspecting the class hierarchy to find where the generic
     * type is declared, then resolving *that* reference.
     *
     * For instance, given:
     *
     * ```
     * interface SomeFactory : Function1<String, SomeClass>
     * ```
     *
     * There's an invisible `fun invoke(p1: P1): R`. If `SomeFactory` is parsed using PSI (such
     * as if it's generated), then the function can only be parsed to have the projected types
     * of `P1` and `R`
     *
     * @receiver The class which actually references the type. In the above example, this
     * would be `SomeFactory`.
     * @param declaringClass The class which declares the generic type name. In the above
     * example, this would be `Function1`.
     * @param typeToResolve The generic reference to be resolved. In the above example, this
     * is the `T` **return type**.
     */
    public fun resolveGenericKotlinTypeOrNull(
      declaringClass: ClassReference,
      typeToResolve: KotlinType
    ): KtTypeReference? {
      val parameterKotlinType = when (typeToResolve) {
        is FlexibleType -> {
          // A FlexibleType comes from Java code where the compiler doesn't know whether it's a
          // nullable or non-nullable type. toString() returns something like "(T..T?)". To get
          // proper results we use the type that's not nullable, "T" in this example.
          if (typeToResolve.lowerBound.isMarkedNullable) {
            typeToResolve.upperBound
          } else {
            typeToResolve.lowerBound
          }
        }
        is DefinitelyNotNullType -> {
          // This is known to be not null in Java, such as something annotated `@NotNull` or
          // controlled by a JSR305 or jSpecify annotation.
          // This is a special type and this logic appears to match how kotlinc is handling it here
          // https://github.com/JetBrains/kotlin/blob/9ee0d6b60ac4f0ea0ccc5dd01146bab92fabcdf2/core/descriptors/src/org/jetbrains/kotlin/types/TypeUtils.java#L455-L458
          typeToResolve.original
        }
        else -> {
          typeToResolve
        }
      }

      return clazz.resolveGenericTypeReference(declaringClass, parameterKotlinType.toString())
    }

    /**
     * Safely resolves a PSI [KtTypeReference], when that type reference may be a generic
     * expressed by a type variable name. This is done by inspecting the class hierarchy to
     * find where the generic type is declared, then resolving *that* reference.
     *
     * For instance, given:
     *
     * ```
     * interface Factory<T> {
     *   fun create(): T
     * }
     *
     * interface ServiceFactory : Factory<Service>
     * ```
     *
     * The KtTypeReference `T` will fail to resolve, since it isn't a type. This function will
     * instead look to the `ServiceFactory` interface, then look at the supertype declaration
     * in order to determine the type.
     *
     * @receiver The class which actually references the type. In the above example, this would
     * be `ServiceFactory`.
     * @param typeReference The generic reference to be resolved. In the above example, this
     * is `T`.
     */
    public fun resolveTypeReference(typeReference: KtTypeReference): KtTypeReference? {

      // if the element isn't a type variable name like `T`, it can be resolved through imports.
      typeReference.typeElement?.fqNameOrNull(module)
        ?.let { return typeReference }

      val declaringClass = typeReference.requireContainingClassReference(module)
      val parameterName = typeReference.text

      return clazz.resolveGenericTypeReference(declaringClass, parameterName)
    }

    private fun KtClassOrObject.resolveGenericTypeReference(
      declaringClass: ClassReference,
      parameterName: String
    ): KtTypeReference? {
      val declaringClassFqName = declaringClass.fqName

      // If the class/interface declaring the generic is the receiver class,
      // then the generic hasn't been set to a concrete type and can't be resolved.
      if (requireFqName() == declaringClassFqName) {
        return null
      }

      // Used to determine which parameter to look at in a KtTypeArgumentList
      val indexOfType = declaringClass.indexOfTypeParameter(parameterName)

      // Find where the supertype is actually declared by matching the FqName of the
      // SuperTypeListEntry to the type which declares the generic we're trying to resolve.
      // After finding the SuperTypeListEntry, just take the TypeReference from its type
      // argument list.
      val resolvedTypeReference = superTypeListEntryOrNull(module, declaringClassFqName)
        ?.typeReference
        ?.typeElement
        ?.getChildOfType<KtTypeArgumentList>()
        ?.arguments
        ?.get(indexOfType)
        ?.typeReference

      // TODO: check if the first line can be removed.
      return if (resolvedTypeReference != null) {
        // This will check that the type can be imported.
        resolveTypeReference(resolvedTypeReference)
      } else {
        null
      }
    }
  }

  public class Descriptor(
    public val clazz: ClassDescriptor,
    override val classId: ClassId,
    override val module: AnvilModuleDescriptor
  ) : ClassReference() {
    override val fqName: FqName = classId.asSingleFqName()

    override val constructors: List<FunctionReference.Descriptor> by lazy(NONE) {
      clazz.constructors.map { it.toFunctionReference(this) }
    }

    override val functions: List<FunctionReference.Descriptor> by lazy(NONE) {
      clazz.unsubstitutedMemberScope
        .getContributedDescriptors(kindFilter = DescriptorKindFilter.FUNCTIONS)
        .filterIsInstance<FunctionDescriptor>()
        .filterNot { it is ConstructorDescriptor }
        .map { it.toFunctionReference(this) }
    }

    override val annotations: List<AnnotationReference> by lazy(NONE) {
      clazz.annotations.map { it.toAnnotationReference(this, module) }
    }

    override val properties: List<PropertyReference.Descriptor> by lazy(NONE) {
      clazz.unsubstitutedMemberScope
        .getDescriptorsFiltered(kindFilter = DescriptorKindFilter.VALUES)
        .filterIsInstance<PropertyDescriptor>()
        .map { it.toPropertyReference(this) }
    }

    private val directSuperClassReferences: List<ClassReference> by lazy(NONE) {
      listOfNotNull(clazz.getSuperClassNotAny())
        .plus(clazz.getSuperInterfaces())
        .map { it.toClassReference(module) }
    }

    private val enclosingClassesWithSelf by lazy(NONE) {
      clazz.parents
        .filterIsInstance<ClassDescriptor>()
        .map { it.toClassReference(module) }
        .toList()
        .reversed()
        .plus(this)
    }

    private val innerClasses by lazy(NONE) {
      clazz.unsubstitutedMemberScope
        .getContributedDescriptors(kindFilter = DescriptorKindFilter.CLASSIFIERS)
        .filterIsInstance<ClassDescriptor>()
        .map { it.toClassReference(module) }
    }

    override fun isInterface(): Boolean = clazz.kind == ClassKind.INTERFACE

    override fun isAbstract(): Boolean = clazz.modality == ABSTRACT

    override fun isObject(): Boolean = DescriptorUtils.isObject(clazz)

    override fun isCompanion(): Boolean = DescriptorUtils.isCompanionObject(clazz)

    override fun visibility(): Visibility {
      return when (val visibility = clazz.visibility) {
        DescriptorVisibilities.PUBLIC -> PUBLIC
        DescriptorVisibilities.INTERNAL -> INTERNAL
        DescriptorVisibilities.PROTECTED -> PROTECTED
        DescriptorVisibilities.PRIVATE -> PRIVATE
        else -> throw AnvilCompilationExceptionClassReference(
          classReference = this,
          message = "Couldn't get visibility $visibility for class $fqName."
        )
      }
    }

    override fun directSuperClassReferences(): List<ClassReference> = directSuperClassReferences

    override fun enclosingClassesWithSelf(): List<Descriptor> = enclosingClassesWithSelf

    override fun innerClasses(): List<Descriptor> = innerClasses

    override fun companionObjects(): List<Descriptor> {
      return super.companionObjects().cast()
    }
  }
}

@ExperimentalAnvilApi
public fun ClassDescriptor.toClassReference(module: ModuleDescriptor): Descriptor =
  module.asAnvilModuleDescriptor().getClassReference(this)

@ExperimentalAnvilApi
public fun KtClassOrObject.toClassReference(module: ModuleDescriptor): Psi =
  module.asAnvilModuleDescriptor().getClassReference(this)

/**
 * Attempts to resolve the [FqName] to a [ClassDescriptor] first, then falls back to a
 * [KtClassOrObject] if the descriptor resolution fails. This will happen if the code being parsed
 * was generated as part of the compilation round for this module.
 */
@ExperimentalAnvilApi
public fun FqName.toClassReferenceOrNull(module: ModuleDescriptor): ClassReference? =
  module.asAnvilModuleDescriptor().getClassReferenceOrNull(this)

@ExperimentalAnvilApi
public fun FqName.toClassReference(module: ModuleDescriptor): ClassReference {
  return toClassReferenceOrNull(module)
    ?: throw AnvilCompilationException("Couldn't resolve ClassReference for $this.")
}

@ExperimentalAnvilApi
public fun ClassReference.isAnnotatedWith(fqName: FqName): Boolean =
  annotations.any { it.fqName == fqName }

@ExperimentalAnvilApi
public fun ClassReference.generateClassName(
  separator: String = "_",
  suffix: String = ""
): ClassId {
  val className = enclosingClassesWithSelf().joinToString(separator = separator) { it.shortName }
  return ClassId(packageFqName, FqName(className + suffix), false)
}

@ExperimentalAnvilApi
public fun ClassReference.asClassName(): ClassName = classId.asClassName()

/**
 * This will return all super types as [ClassReference], whether they're parsed as [KtClassOrObject]
 * or [ClassDescriptor]. This will include generated code, assuming it has already been generated.
 * The returned sequence will be distinct by FqName, and Psi types are preferred over Descriptors.
 *
 * The first elements in the returned sequence represent the direct superclass to the receiver. The
 * last elements represent the types which are furthest up-stream.
 *
 * @param includeSelf If true, the receiver class is the first element of the sequence
 */
@ExperimentalAnvilApi
public fun ClassReference.allSuperTypeClassReferences(
  includeSelf: Boolean = false
): Sequence<ClassReference> {
  return generateSequence(sequenceOf(this)) { superTypes ->
    superTypes
      .map { classRef -> classRef.directSuperClassReferences() }
      .flatten()
      .takeIf { it.firstOrNull() != null }
  }
    .drop(if (includeSelf) 0 else 1)
    .flatten()
    .distinctBy { it.fqName }
}

/**
 * @param parameterName The name of the parameter to be found, not including any variance modifiers.
 * @return The 0-based index of a declared generic type.
 */
@ExperimentalAnvilApi
public fun ClassReference.indexOfTypeParameter(parameterName: String): Int {
  return when (this) {
    is Descriptor ->
      clazz.declaredTypeParameters
        .indexOfFirst { it.name.asString() == parameterName }
    is Psi ->
      clazz.typeParameters
        .indexOfFirst { it.identifyingElement?.text == parameterName }
  }
}

@ExperimentalAnvilApi
public fun ClassReference.scopeOrNull(
  annotationFqName: FqName
): FqName? {
  return when (this) {
    is Descriptor -> clazz.annotationOrNull(annotationFqName)
      ?.scope(module)
      ?.fqNameSafe
    is Psi -> clazz.scopeOrNull(annotationFqName, module)
  }
}

@ExperimentalAnvilApi
@Suppress("FunctionName")
public fun AnvilCompilationExceptionClassReference(
  classReference: ClassReference,
  message: String,
  cause: Throwable? = null
): AnvilCompilationException = when (classReference) {
  is Psi -> AnvilCompilationException(
    element = classReference.clazz.identifyingElement ?: classReference.clazz,
    message = message,
    cause = cause
  )
  is Descriptor -> AnvilCompilationException(
    classDescriptor = classReference.clazz,
    message = message,
    cause = cause
  )
}
