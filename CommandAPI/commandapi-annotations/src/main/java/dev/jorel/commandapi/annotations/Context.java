package dev.jorel.commandapi.annotations;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

import dev.jorel.commandapi.CommandPermission;
import dev.jorel.commandapi.annotations.annotations.Command;
import dev.jorel.commandapi.annotations.annotations.Help;
import dev.jorel.commandapi.annotations.annotations.NeedsOp;
import dev.jorel.commandapi.annotations.annotations.NodeName;
import dev.jorel.commandapi.annotations.annotations.Permission;
import dev.jorel.commandapi.annotations.annotations.Subcommand;
import dev.jorel.commandapi.annotations.annotations.Suggests;
import dev.jorel.commandapi.annotations.annotations.WithoutPermission;
import dev.jorel.commandapi.annotations.arguments.ACustomArgument;
import dev.jorel.commandapi.annotations.arguments.Primitive;
import dev.jorel.commandapi.annotations.parser.ArgumentData;
import dev.jorel.commandapi.annotations.parser.CommandData;
import dev.jorel.commandapi.annotations.parser.SubcommandMethod;
import dev.jorel.commandapi.annotations.parser.SuggestionClass;
import dev.jorel.commandapi.arguments.ArgumentSuggestions;
import dev.jorel.commandapi.arguments.SafeSuggestions;

public class Context {

	private ProcessingEnvironment processingEnv;
	private Logging logging;

	private CommandData commandData;
	
	// Construct some context :)
	public Context(TypeElement classElement, ProcessingEnvironment processingEnv, Logging logging,
			boolean subCommandClass, CommandData parent) {
		this.processingEnv = processingEnv;
		this.logging = logging;

		this.commandData = new CommandData(classElement, subCommandClass, processingEnv, parent);

		parseCommandClass(classElement, subCommandClass);
	}
	
	public CommandData getCommandData() {
		return this.commandData;
	}

	private void parseCommandClass(TypeElement typeElement, boolean subCommandClass) {

		if (subCommandClass) {
			Subcommand subcommandAnnotation = typeElement.getAnnotation(Subcommand.class);
			logging.info(typeElement, "Parsing '" + Arrays.toString(subcommandAnnotation.value()) + "' class");
		} else {
			Command commandAnnotation = typeElement.getAnnotation(Command.class);
			logging.info(typeElement, "Parsing '" + Arrays.toString(commandAnnotation.value()) + "' class");

			// Help only exists for @Command
			Help helpAnnotation = typeElement.getAnnotation(Help.class);
			if (helpAnnotation != null) {
				commandData.setHelp(helpAnnotation.value(), helpAnnotation.shortDescription());
			}

			if (!Validator.validateCommand(typeElement, commandAnnotation, logging)) {
				return;
			}
			
			final String name = commandAnnotation.value()[0];
			final String[] aliases;
			if (commandAnnotation.value().length == 1) {
				aliases = new String[0];
			} else {
				aliases = new String[commandAnnotation.value().length - 1];
				System.arraycopy(commandAnnotation.value(), 1, aliases, 0, commandAnnotation.value().length - 1);
			}
			commandData.setName(name);
			commandData.setAliases(aliases);
		}

		// Parse annotations on inner fields, classes and methods
		Annotation annotation = null;
		for (Element typeElementChild : typeElement.getEnclosedElements()) {
			switch (typeElementChild.getKind()) {
				case CLASS:
					// @Command classes with classes with @Command is invalid
					if (typeElementChild.getAnnotation(Command.class) != null) {
						logging.complain(typeElementChild,
								"Inner class of a @Command class cannot contain another @Command class");
					}

					// Parse @Subcommand classes
					annotation = typeElementChild.getAnnotation(Subcommand.class);
					if (annotation != null) {

						Subcommand subcommandAnnotation = typeElementChild.getAnnotation(Subcommand.class);
						final String name = subcommandAnnotation.value()[0];
						final String[] aliases;
						if (subcommandAnnotation.value().length == 1) {
							aliases = new String[0];
						} else {
							aliases = new String[subcommandAnnotation.value().length - 1];
							System.arraycopy(subcommandAnnotation.value(), 1, aliases, 0, subcommandAnnotation.value().length - 1);
						}
						
						Context subCommandContext = new Context((TypeElement) typeElementChild, processingEnv, logging, true, commandData);
						subCommandContext.commandData.setName(name);
						subCommandContext.commandData.setAliases(aliases);
						
						commandData.addSubcommandClass(subCommandContext.commandData);
					}

					// DON'T parse @Suggestion - this annotation is effectively redundant, we
					// get all of the information about this from @Suggests, by linking to the
					// relevant type mirror instead :)
					// Parse @Suggestion classes
//					typeElementChild.getAnnotation(Suggestion.class);
//					if(typeElementChild.getAnnotation(Suggestion.class) != null) {
//						typeCheckSuggestionClass((TypeElement) typeElementChild);
//					}
					break;
				case METHOD:
					// Parse methods with @Subcommand
					annotation = typeElementChild.getAnnotation(Subcommand.class);
					if (annotation != null) {
						commandData.addSubcommandMethod(parseSubcommandMethod((ExecutableElement) typeElementChild, (Subcommand) annotation));
					}
					break;
				case FIELD:
					annotation = Utils.getArgumentAnnotation(typeElementChild);
					if (annotation != null) {
						commandData.addArgument(parseArgumentField((VariableElement) typeElementChild, annotation, true));
					}
					break;
				default:
					// We don't care about this element :)
					break;
			}
		}
	}

	/**
	 * Determines the permission on this element:
	 * <ul>
	 * <li>If this element has {@code @Permission}, this method returns a
	 * CommandPermission from that.</li>
	 * <li>If this element has {@code @WithoutPermission}, this method returns a negated
	 * CommandPermission.</li>
	 * <li>If this element has {@code @NeedsOp}, this method returns a CommandPermission
	 * with operator privileges.</li>
	 * <li>If this element does not have any permission-related annotations, this
	 * method returns CommandPermission.NONE</li>
	 * </ul>
	 * 
	 * @param element the element with a permission-related annotation
	 * @return a CommandPermission representing the permission required to use this
	 *         element
	 */
	private CommandPermission parsePermission(Element element) {
		// Parse permissions
		if (element.getAnnotation(NeedsOp.class) != null) {
			return CommandPermission.OP;
		} else if (element.getAnnotation(Permission.class) != null) {
			return CommandPermission.fromString(element.getAnnotation(Permission.class).value());
		} else if (element.getAnnotation(WithoutPermission.class) != null) {
			return CommandPermission.fromString(element.getAnnotation(WithoutPermission.class).value()).negate();
		} else {
			return CommandPermission.NONE;
		}
	}
	
	/**
	 * Returns the node name for this element, using the {@code @NodeName} element if one is present. 
	 * @param element
	 * @return
	 */
	private String parseNodeName(Element element) {
		if (element.getAnnotation(NodeName.class) != null) {
			return element.getAnnotation(NodeName.class).value();
		} else {
			return element.getSimpleName().toString();
		}
	}

	/**
	 * Parses argument fields - any class fields or method parameters with any
	 * argument annotation declared in Annotations.ARGUMENT_ANNOTATIONS
	 */
	private ArgumentData parseArgumentField(VariableElement varElement, Annotation annotation, boolean classArgument) {
		// Validate
		Validator.validatePermissions(varElement, logging);

		// Parse permissions
		final CommandPermission permission = parsePermission(varElement);

		// Parse argument node name
		final String nodeName = parseNodeName(varElement);

		// Parse suggestions, via @Suggests
		final Optional<TypeMirror> suggests;
		final Optional<SuggestionClass> suggestionsClass;
		if (varElement.getAnnotation(Suggests.class) != null) {
			TypeMirror suggestsMirror = Utils.getAnnotationClassValue(varElement, Suggests.class);
			suggests = Optional.of(suggestsMirror);
			suggestionsClass = Optional
					.of(typeCheckSuggestionClass((TypeElement) processingEnv.getTypeUtils().asElement(suggestsMirror)));
		} else {
			suggests = Optional.empty();
			suggestionsClass = Optional.empty();
		}
		
		// Validate argument type
		
		if(!annotation.annotationType().equals(ACustomArgument.class)) {
			TypeMirror[] primitives = Utils.getPrimitiveTypeMirror(annotation.annotationType().getAnnotation(Primitive.class), processingEnv);
			final TypeMirror varType = varElement.asType(); // TODO: Apply type erasure
			
//			System.out.println(Arrays.deepToString(primitives) + " c.f. " + varType );
			
			if(!Arrays.stream(primitives).anyMatch((TypeMirror x) -> {
				if(varType.getKind().isPrimitive()) {
					return processingEnv.getTypeUtils().isSameType(processingEnv.getTypeUtils().boxedClass(processingEnv.getTypeUtils().getPrimitiveType(varType.getKind())).asType(), x);
				} else {
					return processingEnv.getTypeUtils().isSameType(x, varType);
				}
			})) {
				logging.complain(varElement, "Mismatched argument types. This argument of type " + varType + " does not match @" + Utils.getArgumentAnnotation(varElement).annotationType().getSimpleName());
			}
		}

		// Create ArgumentData
		ArgumentData argumentData = new ArgumentData(varElement, annotation, permission, nodeName, suggests,
				suggestionsClass, commandData, classArgument);
		if (suggestionsClass.isPresent()) {
			argumentData.validateSuggestionsClass(processingEnv);
		}
		
		return argumentData;
	}

	private SubcommandMethod parseSubcommandMethod(ExecutableElement methodElement, Subcommand subcommandAnnotation) {
		logging.info(methodElement, "Parsing '" + Arrays.toString(subcommandAnnotation.value()) + "' method");

		// Check that there is at least one parameter
		List<? extends VariableElement> parameters = methodElement.getParameters();
		if (parameters.isEmpty()) {
			logging.complain(methodElement, "This method has no valid CommandSender parameter!");
			return null;
		}

		// Check if the first parameter is a CommandSender (or instance thereof)
		try {
			if (!Utils.isValidSender(parameters.get(0).asType())) {
				logging.complain(methodElement, parameters.get(0).asType() + " is not a valid CommandSender");
				return null;
			}
		} catch (ClassNotFoundException e) {
			logging.complain(methodElement,
					"Could not load class information for '" + parameters.get(0).asType() + "'");
			e.printStackTrace();
			return null;
		}

		if (!Validator.validateSubCommand(methodElement, subcommandAnnotation, logging)) {
			return null;
		}

		final String name = subcommandAnnotation.value()[0];
		final String[] aliases;
		if (subcommandAnnotation.value().length == 1) {
			aliases = new String[0];
		} else {
			aliases = new String[subcommandAnnotation.value().length - 1];
			System.arraycopy(subcommandAnnotation.value(), 1, aliases, 0, subcommandAnnotation.value().length - 1);
		}
		
		final CommandPermission permission = parsePermission(methodElement);
		
		List<ArgumentData> arguments = new ArrayList<>();
		
		for(int i = 1; i < parameters.size(); i++) {
			VariableElement parameter = parameters.get(i);
			Annotation annotation = Utils.getArgumentAnnotation(parameter);
			if(annotation == null) {
				logging.complain(parameter, "Argument is missing a CommandAPI argument annotation. " + Utils.predictAnnotation(parameter));
			} else {
				arguments.add(parseArgumentField(parameter, annotation, false));
			}
		}
		
		boolean isResulting;
		if(methodElement.getReturnType().getKind() == TypeKind.VOID) {
			isResulting = false;
		} else if(methodElement.getReturnType().getKind() == TypeKind.INT) {
			isResulting = true;
		} else {
			logging.complain(methodElement, "@Subcommand method return type must be 'void' or 'int'");
			return null;
		}

		return new SubcommandMethod(methodElement, name, aliases, permission, arguments, isResulting, this.commandData);
	}

	private SuggestionClass typeCheckSuggestionClass(TypeElement typeElement) {
		logging.info(typeElement,
				"Checking type signature for @Suggests class '" + typeElement.getSimpleName() + "' class");

		// java.util.function.Supplier<dev.jorel.commandapi.arguments.ArgumentSuggestions>
		// java.util.function.Supplier<dev.jorel.commandapi.arguments.SafeSuggestions<org.bukkit.Location>>

		// Get the interfaces (e.g. Supplier<ArgumentSuggestions> or
		// Supplier<SafeSuggestions<Location>>)

		Types types = processingEnv.getTypeUtils();
		TypeMirror argumentSuggestionsMirror = processingEnv.getElementUtils()
				.getTypeElement(ArgumentSuggestions.class.getCanonicalName()).asType();
		TypeMirror safeSuggestionsMirror = processingEnv.getElementUtils()
				.getTypeElement(SafeSuggestions.class.getCanonicalName()).asType();

		TypeMirror supplierMirror = null;

		for (TypeMirror mirror : typeElement.getInterfaces()) {

			final TypeMirror supplier = processingEnv.getElementUtils()
					.getTypeElement(Supplier.class.getCanonicalName()).asType();
			if (!types.isSameType(types.erasure(supplier), types.erasure(mirror))) {
				logging.complain(types.asElement(mirror),
						"@Suggests class must implement java.util.function.Supplier directly");
			}

			supplierMirror = mirror;
		}

		if (supplierMirror == null) {
			logging.complain(typeElement, "@Suggests class must implement java.util.function.Supplier");
		}

		// We want to inspect the generics (e.g. ArgumentSuggestions or
		// SafeSuggestions<Location>
		if (supplierMirror instanceof DeclaredType declaredType) {
			for (TypeMirror typeArgument : declaredType.getTypeArguments()) {
				if (types.isSameType(argumentSuggestionsMirror, typeArgument)) {
					return new SuggestionClass(typeElement, processingEnv);
				} else if (types.isSameType(types.erasure(safeSuggestionsMirror), types.erasure(typeArgument))) {
					// TODO: More type checking here
					return new SuggestionClass(typeElement, processingEnv);
				} else {
					logging.complain(typeElement,
							"@Suggests class's Supplier has an invalid type argument. Expected Supplier<ArgumentSuggestions> or Supplier<SafeSuggestions>");
				}
			}
		}

		return null;
	}

	/**
	 * Main starting entrypoint - generates contexts for all elements with @Command
	 * 
	 * @param commandClasses classes with @Command. Each element is assumed to be a
	 *                       TypeElement
	 * @param processingEnv  the processing environment from the annotation
	 *                       processor
	 * @param logging        logging class
	 * @return
	 */
	public static Map<Element, Context> generateContexts(Set<? extends Element> commandClasses,
			ProcessingEnvironment processingEnv, Logging logging) {
		Map<Element, Context> contextMap = new HashMap<>();
		for (Element classElement : commandClasses) {
			contextMap.put(classElement, new Context((TypeElement) classElement, processingEnv, logging, false, null));
		}
		return contextMap;
	}

}
