package com.maddyhome.idea.vim;

/*
 * IdeaVim - A Vim emulator plugin for IntelliJ Idea
 * Copyright (C) 2003 Rick Maddy
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.TypedActionHandler;
import com.maddyhome.idea.vim.command.Argument;
import com.maddyhome.idea.vim.command.Command;
import com.maddyhome.idea.vim.command.CommandState;
import com.maddyhome.idea.vim.group.CommandGroups;
import com.maddyhome.idea.vim.group.RegisterGroup;
import com.maddyhome.idea.vim.key.ArgumentNode;
import com.maddyhome.idea.vim.key.BranchNode;
import com.maddyhome.idea.vim.key.CommandNode;
import com.maddyhome.idea.vim.key.KeyParser;
import com.maddyhome.idea.vim.key.Node;
import com.maddyhome.idea.vim.key.ParentNode;
import com.maddyhome.idea.vim.helper.RunnableHelper;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Stack;
import javax.swing.KeyStroke;

/**
 * This handlers every keystroke that the user can argType except those that are still valid hotkeys for various
 * Idea actions. This is a singleton.
 */
public class KeyHandler
{
    /**
     * Returns a reference to the singleton instance of this class
     * @return A reference to the singleton
     */
    public static KeyHandler getInstance()
    {
        if (instance == null)
        {
            instance = new KeyHandler();
        }

        return instance;

    }

    /**
     * Creates an instance
     */
    private KeyHandler()
    {
        reset();
    }

    /**
     * Sets the original key handler
     * @param origHandler The original key handler
     */
    public void setOriginalHandler(TypedActionHandler origHandler)
    {
        this.origHandler = origHandler;
    }

    /**
     * Gets the original key handler
     * @return The orginal key handler
     */
    public TypedActionHandler getOriginalHandler()
    {
        return origHandler;
    }

    /**
     * This is the main key handler for the Vim plugin. Every keystroke not handled directly by Idea is sent
     * here for processing.
     * @param editor The editor the key was typed into
     * @param key The keystroke typed by the user
     * @param context The data context
     */
    public void handleKey(Editor editor, KeyStroke key, DataContext context)
    {
        // If this is a "regular" character keystroke, get the character
        char chKey = key.getKeyChar() == KeyEvent.CHAR_UNDEFINED ? 0 : key.getKeyChar();

        if (CommandState.getInstance().getMode() == CommandState.MODE_COMMAND &&
            (key.getKeyCode() == KeyEvent.VK_ESCAPE ||
            (key.getKeyCode() == KeyEvent.VK_C && (key.getModifiers() & KeyEvent.CTRL_MASK) != 0) ||
            (key.getKeyCode() == '[' && (key.getModifiers() & KeyEvent.CTRL_MASK) != 0)))
        {
            if (count == 0 && currentArg == Argument.NONE && currentCmd.size() == 0 &&
                CommandGroups.getInstance().getRegister().getCurrentRegister() == RegisterGroup.REGISTER_DEFAULT)
            {
               VimPlugin.indicateError();
            }

            fullReset();
        }
        // At this point the user must be typing in a command. Most commands can be preceeded by a number. Let's
        // check if a number can be entered at this point, and if so, did the user send us a digit.
        else if ((CommandState.getInstance().getMode() == CommandState.MODE_COMMAND ||
            CommandState.getInstance().getMode() == CommandState.MODE_VISUAL) &&
            mode == STATE_NEW_COMMAND && currentArg != Argument.CHARACTER && Character.isDigit(chKey) &&
            (count != 0 || chKey != '0'))
        {
            // Update the count
            count = count * 10 + (chKey - '0');
        }
        // Pressing delete while entering a count "removes" the last digit entered
        else if ((CommandState.getInstance().getMode() == CommandState.MODE_COMMAND ||
            CommandState.getInstance().getMode() == CommandState.MODE_VISUAL) &&
            mode == STATE_NEW_COMMAND && currentArg != Argument.CHARACTER &&
            key.getKeyCode() == KeyEvent.VK_DELETE && count != 0)
        {
            // "Remove" the last digit sent to us
            count /= 10;
        }
        // If we got this far the user is entering a command or supplying an argument to an entered command.
        // First let's check to see if we are at the point of expecting a single character argument to a command.
        else if (currentArg == Argument.CHARACTER)
        {
            // We are expecting a character argument - is this a regular character the user typed?
            if (chKey != 0)
            {
                // Create the character argument, add it to the current command, and signal we are ready to process
                // the command
                Argument arg = new Argument(chKey);
                Command cmd = (Command)currentCmd.peek();
                cmd.setArgument(arg);
                mode = STATE_READY;
            }
            else
            {
                // Oops - this isn't a valid character argument
                mode = STATE_ERROR;
            }
        }
        // If we are this far - sheesh, then the user must be entering a command or a non-single-character argument
        // to an entered command. Let's figure out which it is
        else
        {
            // For debugging purposes we track the keys entered for this command
            keys.add(key);
            logger.debug("keys now " + keys);

            // Ask the key/action tree if this is an appropriate key at this point in the command and if so,
            // return the node matching this keystroke
            Node node = currentNode.getChild(key);
            // If this is a branch node we have entered only part of a multikey command
            if (node instanceof BranchNode)
            {
                // Flag that we aren't allowing any more count digits
                mode = STATE_COMMAND;
                currentNode = (BranchNode)node;
            }
            // If this is a command node the user has entered a valid key sequence of a know command
            else if (node instanceof CommandNode)
            {
                // If all does well we are ready to process this command
                mode = STATE_READY;
                CommandNode cmdNode = (CommandNode)node;
                // Did we just get the completed sequence for a motion command argument?
                if (currentArg == Argument.MOTION)
                {
                    // We have been expecting a motion argument - is this one?
                    if (cmdNode.getCmdType() == Command.MOTION)
                    {
                        // Create the motion command and add it to the stack
                        Command cmd = new Command(count, cmdNode.getAction(), cmdNode.getCmdType(), cmdNode.getFlags());
                        currentCmd.push(cmd);
                    }
                    else if (cmdNode.getCmdType() == Command.RESET)
                    {
                        currentCmd.clear();
                        Command cmd = new Command(1, cmdNode.getAction(), cmdNode.getCmdType(), cmdNode.getFlags());
                        currentCmd.push(cmd);
                    }
                    else
                    {
                        // Oops - this wasn't a motion command. The user goofed and typed something else
                        mode = STATE_ERROR;
                    }
                }
                // The user entered a valid command that doesn't take any arguments
                else
                {
                    // Create the command and add it to the stack
                    Command cmd = new Command(count, cmdNode.getAction(), cmdNode.getCmdType(), cmdNode.getFlags());
                    currentCmd.push(cmd);

                    // This is a sanity check that the command has a valid action. This should only fail if the
                    // programmer made a typo or forgot to add the action to the plugin.xml file
                    if (cmd.getAction() == null)
                    {
                        logger.error("NULL action for keys '" + keys + "'");
                        mode = STATE_ERROR;
                    }
                }
            }
            // If this is an argument node then the last keystroke was not part of the current command but should
            // be the first keystroke of the current command's argument
            else if (node instanceof ArgumentNode)
            {
                // Create a new command based on what the user has typed so far, excluding this keystroke.
                ArgumentNode arg = (ArgumentNode)node;
                Command cmd = new Command(count, arg.getAction(), arg.getCmdType(), arg.getFlags());
                currentCmd.push(cmd);
                // What argType of argument does this command expect?
                switch (arg.getArgType())
                {
                    case Argument.CHARACTER:
                    case Argument.MOTION:
                        mode = STATE_NEW_COMMAND;
                        currentArg = arg.getArgType();
                        // Is the current command an operator? If so set the state to only accept "operator pending"
                        // commands
                        if ((arg.getFlags() & KeyParser.FLAG_OP_PEND) != 0)
                        {
                            CommandState.getInstance().setMappingMode(KeyParser.MAPPING_OP_PEND);
                        }
                        break;
                    default:
                        // Oops - we aren't expecting any other argType of argument
                        mode = STATE_ERROR;
                }

                // If the current keystroke is really the first character of an argument the user needs to enter,
                // recursively go back and handle this keystroke again with all the state properly updated to
                // handle the argument
                if (currentArg != Argument.NONE)
                {
                    partialReset();
                    handleKey(editor, key, context);
                }
            }
            else
            {
                // If we are in insert/replace mode send this key in for processing
                if (CommandState.getInstance().getMode() == CommandState.MODE_INSERT ||
                    CommandState.getInstance().getMode() == CommandState.MODE_REPLACE)
                {
                    CommandGroups.getInstance().getChange().processKey(editor, context, key);
                }
                // If we get here then the user has entered an unrecognized series of keystrokes
                else
                {
                    mode = STATE_ERROR;
                }
            }
        }

        // Do we have a fully entere command at this point? If so, lets execute it
        if (mode == STATE_READY)
        {
            // Let's go through the command stack and merge it all into one command. At this time there should never
            // be more than two commands on the stack - one is the actual command and the other would be a motion
            // command argument needed by the first command
            Command cmd = (Command)currentCmd.pop();
            while (currentCmd.size() > 0)
            {
                Command top = (Command)currentCmd.pop();
                top.setArgument(new Argument(cmd));
                cmd = top;
            }

            // If we have a command and a motion command argument, both could possibly have their own counts. We
            // need to adjust the counts so the motion gets the product of both counts and the command's count gets
            // reset. Example 3c2w (change 2 words, three times) becomes c6w (change 6 words)
            Argument arg = cmd.getArgument();
            if (arg != null && arg.getType() == Argument.MOTION)
            {
                Command mot = arg.getMotion();
                // If no count was entered for either command then nothing changes. If either had a count then
                // the motion gets the product of both.
                int cnt = cmd.getRawCount() == 0 && mot.getRawCount() == 0 ? 0 : cmd.getCount() * mot.getCount();
                cmd.setCount(0);
                mot.setCount(cnt);
            }

            // If we were in "operator pending" mode, reset back to normal mode.
            if (CommandState.getInstance().getMappingMode() == KeyParser.MAPPING_OP_PEND)
            {
                CommandState.getInstance().setMappingMode(KeyParser.MAPPING_NORMAL);
            }

            // Save off the command we are about to execute
            CommandState.getInstance().setCommand(cmd);

            if (!editor.getDocument().isWritable() && !Command.isReadOnlyType(cmd.getType()))
            {
                VimPlugin.indicateError();
                reset();
            }
            else
            {
                Runnable action = new ActionRunner(editor, context, cmd);
                if (Command.isReadOnlyType(cmd.getType()))
                {
                    RunnableHelper.runReadCommand(action);
                }
                else
                {
                    RunnableHelper.runWriteCommand(action);
                }
            }
        }
        // We had some sort of error so reset the handler and let the user know (beep)
        else if (mode == STATE_ERROR)
        {
            VimPlugin.indicateError();
            fullReset();
        }
    }

    /**
     * Execute an action by name
     * @param name The name of the action to execute
     * @param context The context to run it in
     */
    public static void executeAction(String name, DataContext context)
    {
        logger.debug("executing action " + name);
        ActionManager aMgr = ActionManager.getInstance();
        AnAction action = aMgr.getAction(name);
        if (action != null)
        {
            executeAction(action, context);
        }
        else
        {
            logger.debug("Unknown action");
        }
    }

    /**
     * Execute an action
     * @param action The action to execute
     * @param context The context to run it in
     */
    public static void executeAction(AnAction action, DataContext context)
    {
        logger.debug("executing action " + action);

        // Hopefully all the arguments are sufficient. So far they all seem to work OK.
        // We don't have a specific InputEvent so that is null
        // What is "place"? Leave it the empty string for now.
        // Is the template presentation sufficient?
        // What are the modifiers? Is zero OK?
        action.actionPerformed(new AnActionEvent(null, context, "", action.getTemplatePresentation(), 0));
    }

    /**
     * Partially resets the state of this handler. Resets the command count, clears the key list, resets the
     * key tree node to the root for the current mode we are in.
     */
    private void partialReset()
    {
        count = 0;
        keys = new ArrayList();
        currentNode = KeyParser.getInstance().getKeyRoot(CommandState.getInstance().getMappingMode());
        logger.debug("partialReset");
    }

    /**
     * Resets the state of this handler. Does a partial reset then resets the mode, the command, and the argument
     */
    public void reset()
    {
        partialReset();
        mode = STATE_NEW_COMMAND;
        currentCmd.clear();
        currentArg = Argument.NONE;
        logger.debug("reset");
    }

    /**
     * Completely resets the state of this handler. Resets the command mode to normal, resets, and clears the selected
     * register.
     */
    public void fullReset()
    {
        CommandState.getInstance().setMappingMode(KeyParser.MAPPING_NORMAL);
        reset();
        CommandGroups.getInstance().getRegister().resetRegister();
    }

    /**
     * This was used as an experiment to execute actions as a runnable.
     */
    static class ActionRunner implements Runnable
    {
        public ActionRunner(Editor editor, DataContext context, Command cmd)
        {
            this.editor = editor;
            this.context = context;
            this.cmd = cmd;
        }

        public void run()
        {
            executeAction(cmd.getAction(), context);
            if (CommandState.getInstance().getMode() == CommandState.MODE_INSERT ||
                CommandState.getInstance().getMode() == CommandState.MODE_REPLACE)
            {
                CommandGroups.getInstance().getChange().processCommand(editor, context, cmd);
            }

            // Now that the command has been executed let's clean up a few things.

            // By default the "empty" register is used by all commands so we want to reset whatever the last register
            // selected by the user was to the empty register - unless we just executed the "select register" command.
            if (cmd.getType() != Command.SELECT_REGISTER)
            {
                CommandGroups.getInstance().getRegister().resetRegister();
            }

            KeyHandler.getInstance().reset();

            // If, at this point, we are not in insert, replace, or visual modes, we need to restore the previous
            // mode we were in. This handles commands in those modes that temporarily allow us to execute normal
            // mode commands. An exception is if this command should leave us in the temporary mode such as
            // "select register"
            if ((CommandState.getInstance().getMode() != CommandState.MODE_INSERT &&
                CommandState.getInstance().getMode() != CommandState.MODE_REPLACE &&
                CommandState.getInstance().getMode() != CommandState.MODE_VISUAL) &&
                (cmd.getFlags() & KeyParser.FLAG_EXPECT_MORE) == 0)
            {
                CommandState.getInstance().restoreMode();
            }
        }

        private Editor editor;
        private DataContext context;
        private Command cmd;
    }

    private int count;
    private ArrayList keys;
    private int mode;
    private ParentNode currentNode;
    private Stack currentCmd = new Stack();
    private int currentArg;
    private TypedActionHandler origHandler;

    private static KeyHandler instance;

    private static final int STATE_NEW_COMMAND = 1;
    private static final int STATE_COMMAND = 2;
    private static final int STATE_READY = 3;
    private static final int STATE_ERROR = 4;

    private static Logger logger = Logger.getInstance(KeyHandler.class.getName());
}