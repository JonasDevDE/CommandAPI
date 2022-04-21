package dev.jorel.commandapi.annotations.parser;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.lang.model.element.ExecutableElement;

import com.google.common.collect.Streams;

import dev.jorel.commandapi.CommandPermission;
import dev.jorel.commandapi.annotations.Utils;
import dev.jorel.commandapi.annotations.annotations.Subcommand;
import dev.jorel.commandapi.executors.ExecutorType;

public class SubcommandMethod extends CommandElement {

	private final ExecutableElement methodElement;

	// The executor types. Inferred from the first argument of the method, or explicitly declared via @Executors
	private ExecutorType[] executorTypes;

	private final String subcommandName;

	private final String[] aliases;

	private CommandPermission permission;

	/**
	 * Arguments for this executable element (doesn't include inherited arguments)
	 */
	private List<ArgumentData> arguments;

	// Whether this is a resulting executor or not. If this method returns void, it's not. If this method returns int, it is. If this method returns anything else, this should be caught by semantics (TODO: Implement in semantics)
	private boolean resulting;

	private final CommandData parent;

	public SubcommandMethod(ExecutableElement methodElement, String name, String[] aliases, CommandPermission permission, List<ArgumentData> arguments, boolean isResulting, CommandData parent) {
		this.methodElement = methodElement;
		this.subcommandName = name;
		this.aliases = aliases;
		this.permission = permission;
		this.arguments = arguments;
		this.resulting = isResulting;
		this.parent = parent;
	}

	@Override
	public void emit(PrintWriter out, int currentIndentation) {
		this.indentation = currentIndentation;

		if (methodElement.getAnnotation(Subcommand.class) != null) {

			// MultiLiteralArgument representing this command
			out.print(indentation() + ".withArguments(");
			out.print("new MultiLiteralArgument(");

			out.print(Arrays.stream(Utils.strCons(subcommandName, aliases))
					.map(Utils::quote).collect(Collectors.joining(", ")));
			out.print(")");
			out.print(".setListed(false)");

			// Permissions
			emitPermission(out, permission);

			out.println(")");
			
			for(ArgumentData argument : arguments) {
				argument.emit(out, currentIndentation);
			}

			// TODO: executor type
			out.println(indentation() + ".executes((sender, args) -> {");
			indent();

			if(resulting) {
				out.print("return ");
			}

			emitMethodCallArguments(out);

			dedent();
			out.println(indentation() + "})");
			out.println(indentation() + ".register();");
		} else {
			// TODO: Assert. This object should never have been constructed!
		}
	}

	private void emitMethodCallArguments(PrintWriter out) {

		// We need to derive the path of classes required to get to this suggestion
		// class, from the top-level @Command class
		
		Deque<CommandData> commandTree = new ArrayDeque<>();

		CommandData topLevelCommand = parent;
		while (topLevelCommand.getParent() != null) {
			commandTree.push(topLevelCommand);
			topLevelCommand = topLevelCommand.getParent();
		}
		commandTree.push(topLevelCommand);

		// TODO: Comments describing what's going on here
		int argumentIndex = 0;
		int commandDataIndex = 0;
		for(CommandData commandData : commandTree) {
			
			// If we're not the root level, we have to create the object first
			if(commandDataIndex != 0) {
				out.print(indentation() + commandData.getTypeElement().toString() + " ");
				out.print(Utils.COMMAND_VAR_NAME + commandDataIndex + " = ");
				if(commandDataIndex == 1) {
					out.print(Utils.COMMAND_VAR_NAME);
				} else {
					out.print(Utils.COMMAND_VAR_NAME + (commandDataIndex - 1));
				}
				out.println(".new " + commandData.getTypeElement().getSimpleName() + "();");
			}
			
			// Generate the arguments for the class
			for(int i = 0; i < commandData.getArguments().size(); i++) {
				ArgumentData currentArgument = commandData.getArguments().get(i);
				
				// If we're the root level, we have no prefixed index (e.g. 'command' instead of 'command0')
				out.print(indentation());
				if(commandDataIndex == 0) {
					out.print(Utils.COMMAND_VAR_NAME + ".");
				} else {
					out.print(Utils.COMMAND_VAR_NAME + commandDataIndex + ".");
				}
				
				// Generate the argument assignment
				out.println(currentArgument.getArgumentVariableName() + " = (" + currentArgument.getTypeMirror().toString() + ") args[" + argumentIndex + "];");
				argumentIndex++;
			}
			commandDataIndex++;
		}
		commandDataIndex--;

		out.println();
		
		// Print the actual method call
		out.print(indentation());
		if(commandDataIndex == 0) {
			out.print(Utils.COMMAND_VAR_NAME + ".");
		} else {
			out.print(Utils.COMMAND_VAR_NAME + commandDataIndex + ".");
		}
		out.print(methodElement.getSimpleName() + "(sender");
		for(int i = 0; i < this.arguments.size(); i++) {
			ArgumentData argument = this.arguments.get(i);
			out.print(", (" + argument.getTypeMirror().toString() + ") args[" + (i + argumentIndex) + "]");
		}
		out.println(");");
	}

}
