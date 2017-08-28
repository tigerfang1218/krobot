package org.krobot.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.inject.Singleton;
import net.dv8tion.jda.core.EmbedBuilder;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.Member;
import net.dv8tion.jda.core.entities.MessageEmbed;
import net.dv8tion.jda.core.entities.User;
import org.apache.commons.lang3.ArrayUtils;
import org.krobot.MessageContext;
import org.krobot.permission.BotNotAllowedException;
import org.krobot.permission.BotRequires;
import org.krobot.permission.UserNotAllowedException;
import org.krobot.permission.UserRequires;
import org.krobot.runtime.KrobotRuntime;
import org.krobot.util.UserUtils;

@Singleton
public class CommandManager
{
    private static final Map<String, ArgumentFactory> argumentFactories = new HashMap<>();

    private KrobotRuntime runtime;

    private List<KrobotCommand> commands;
    private List<CommandFilter> filters;

    public CommandManager(KrobotRuntime runtime)
    {
        this.runtime = runtime;

        this.commands = new ArrayList<>();
        this.filters = new ArrayList<>();
    }

    public void handle(MessageContext context)
    {
        String content = context.getMessage().getRawContent().trim();
        String prefix = runtime.getFilterRunner().getPrefix(context);

        String botMention = "<@!" + runtime.jda().getSelfUser().getId() + "> ";

        if (content.startsWith(botMention) && !content.equals(botMention))
        {
            prefix = botMention;
        }

        if (prefix != null && (!content.startsWith(prefix) || content.equals(prefix)))
        {
            return;
        }

        if (content.startsWith(botMention))
        {
            content = content.substring(botMention.length());
        }
        else if (prefix != null && content.startsWith(prefix))
        {
            content = content.substring(prefix.length());
        }

        String[] split = splitWithQuotes(content);

        if (split.length == 0)
        {
            return;
        }

        String label = split[0];

        Optional<KrobotCommand> optional = commands.stream().filter(c -> {
            if (c.getAliases() != null)
            {
                Optional<String> alias = Stream.of(c.getAliases()).filter(a -> a.equalsIgnoreCase(label)).findFirst();

                if (alias.isPresent())
                {
                    return true;
                }
            }

            return c.getLabel().equalsIgnoreCase(label);
        }).findFirst();

        if (!optional.isPresent())
        {
            return;
        }

        KrobotCommand command = optional.get();
        String[] args = ArrayUtils.subarray(split, 1, split.length);

        if (args.length > 0)
        {
            Optional<KrobotCommand> sub = Stream.of(command.getSubCommands()).filter(s -> s.getLabel().equalsIgnoreCase(args[0])).findFirst();

            if (sub.isPresent())
            {
                try
                {
                    execute(context, sub.get(), ArrayUtils.subarray(args, 1, args.length));
                }
                catch (Exception e)
                {
                    runtime.getExceptionHandler().handle(context, command, args, e);
                }

                return;
            }
        }

        if (!command.getHandler().getClass().isAnnotationPresent(NoTyping.class))
        {
            context.getChannel().sendTyping();
        }

        try
        {
            execute(context, command, args);
        }
        catch (Exception e)
        {
            runtime.getExceptionHandler().handle(context, command, args, e);
        }
    }

    public void execute(MessageContext context, KrobotCommand command, String[] args) throws Exception
    {
        CommandHandler handler = command.getHandler();

        if (handler.getClass().isAnnotationPresent(BotRequires.class))
        {
            for (Permission perm : handler.getClass().getAnnotation(BotRequires.class).value())
            {
                if (!context.botHasPermission(perm))
                {
                    throw new BotNotAllowedException(perm);
                }
            }
        }

        if (handler.getClass().isAnnotationPresent(UserRequires.class))
        {
            for (Permission perm : handler.getClass().getAnnotation(UserRequires.class).value())
            {
                if (!context.hasPermission(perm))
                {
                    throw new UserNotAllowedException(perm);
                }
            }
        }

        Map<String, Object> supplied = new HashMap<>();

        int i;

        for (i = 0; i < command.getArguments().length; i++)
        {
            CommandArgument arg = command.getArguments()[i];

            if (i > args.length - 1 && arg.isRequired())
            {
                throw new WrongArgumentNumberException(command, args.length);
            }

            supplied.put(arg.getKey(), arg.getFactory().process(args[0]));
        }

        if (i < args.length - 1)
        {
            throw new WrongArgumentNumberException(command, args.length);
        }

        ArgumentMap argsMap = new ArgumentMap(supplied);

        CommandCall call = new CommandCall(command);
        for (CommandFilter filter : command.getFilters())
        {
            filter.filter(call, context, argsMap);
        }

        if (!call.isCancelled())
        {
            Object result;

            try
            {
                result = command.getHandler().handle(context, argsMap);
            }
            catch (Throwable t)
            {
                runtime.getExceptionHandler().handle(context, command, args, t);
                return;
            }

            if (context.botHasPermission(Permission.MESSAGE_MANAGE))
            {
                context.getMessage().delete().reason("Command triggered").queue();
            }

            if (result != null)
            {
                if (result instanceof EmbedBuilder)
                {
                    context.send((EmbedBuilder) result);
                }
                else if (result instanceof MessageEmbed)
                {
                    context.send((MessageEmbed) result);
                }
                else
                {
                    context.send(result.toString());
                }
            }
        }
    }

    /**
     * Split a message from whitespaces, ignoring the one in quotes.<br><br>
     *
     * <b>Example :</b>
     *
     * <pre>
     *     I am a "discord bot"
     * </pre>
     *
     * Will return ["I", "am", "a", "discord bot"].
     *
     * @param line The line to split
     *
     * @return The line split
     */
    public static String[] splitWithQuotes(String line)
    {
        ArrayList<String> matchList = new ArrayList<>();
        Pattern regex = Pattern.compile("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'");
        Matcher matcher = regex.matcher(line);

        while (matcher.find())
        {
            if (matcher.group(1) != null)
            {
                matchList.add(matcher.group(1));
            }
            else if (matcher.group(2) != null)
            {
                matchList.add(matcher.group(2));
            }
            else
            {
                matchList.add(matcher.group());
            }
        }

        return matchList.toArray(new String[matchList.size()]);
    }

    public List<KrobotCommand> getCommands()
    {
        return commands;
    }

    public List<CommandFilter> getFilters()
    {
        return filters;
    }

    public static void registerArgumentFactory(String name, ArgumentFactory factory)
    {
        argumentFactories.put(name, factory);
    }

    public static ArgumentFactory getArgumentFactory(String key)
    {
        return argumentFactories.get(key);
    }

    static
    {
        registerArgumentFactory("string", a -> a);

        registerArgumentFactory("number", argument -> {
            try
            {
                return Integer.parseInt(argument);
            }
            catch (NumberFormatException e)
            {
                throw new BadArgumentTypeException(argument, "number");
            }
        });

        registerArgumentFactory("float", argument -> {
            try
            {
                return Float.parseFloat(argument);
            }
            catch (NumberFormatException e)
            {
                throw new BadArgumentTypeException(argument, "float");
            }
        });

        registerArgumentFactory("user", argument -> {
            User result = UserUtils.resolve(argument);

            if (result == null)
            {
                throw new BadArgumentTypeException("Can't find user '" + argument + "'", argument, "user");
            }

            return result;
        });

        // Aliases
        registerArgumentFactory("integer", getArgumentFactory("number"));
        registerArgumentFactory("int", getArgumentFactory("number"));
    }
}
