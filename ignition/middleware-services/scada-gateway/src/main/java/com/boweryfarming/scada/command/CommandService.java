package com.boweryfarming.scada.command;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.boweryfarming.scada.AbstractScadaService;
import com.boweryfarming.scada.ServiceContext;
import com.boweryfarming.scada.simulator.SimulatorService;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.keyprovider.AbstractGeneratorHostKeyProvider;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;

import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

/** Manages command communication and execution */
public class CommandService extends AbstractScadaService {
    SshServer sshServer;

    public CommandService(ServiceContext context) {
        super(context);
    }

    @Override
    public void onStart() {
        logger.info("Starting ssh server");

        initCommandOptions();

        Logger l;
        l = (Logger) LoggerFactory.getLogger(SshServer.class);
        l.setLevel(Level.ALL);

        sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(9888);

        AbstractGeneratorHostKeyProvider provider = new SimpleGeneratorHostKeyProvider();
        provider.setAlgorithm("RSA");
        sshServer.setKeyPairProvider(provider);
        sshServer.setPublickeyAuthenticator(new PublickeyAuthenticator() {
            @Override
            public boolean authenticate(String username, PublicKey key, ServerSession session) {
                logger.info(String.format("user:%s", username));
                return true;
            }
        });
        // sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        // sshServer.setPasswordAuthenticator((username, password, serssion) -> {
        //     logger.info(String.format("username:%s password:%s", username, password));
        //     return true;
        // });
        sshServer.setCommandFactory(new ScadaCommandFactory());

        try {
            sshServer.start();
        } catch (IOException ioe) {
            logger.info(String.format("failed to start ssh server: %s", ioe.getMessage()));
        }

        logger.info("ssh server started: " + sshServer.isStarted() + " opened:" + sshServer.isOpen());
        logger.info("ssh server" + sshServer.toString());
    }

    @Override
    public void onDestroy() {
        try {
            logger.info("Closing ssh server");
            sshServer.stop();
            sshServer.close();
        } catch (IOException ioe) {
            logger.info(String.format("failed to stop ssh server: %s", ioe.getMessage()));
        }
    }

    abstract class ScadaCommand {
        protected Options options = new Options();
        protected String description;

        public ScadaCommand(String description) {
            this.description = description;
        }

        public Options getOptions() {
            return options;
        }

        public String getDescription() {
            return description;
        }

        abstract AbstractScadaCommandCallable getCallable(CommandLine cli);
    }

    class ScadaCommandResult {
        int code;
        String message;

        public ScadaCommandResult(int code, String message) {
            this.code = code;
            this.message = message;
        }

        int getCode() {
            return code;
        }

        String getMessage() {
            return message;
        }
    }

    abstract class AbstractScadaCommandCallable implements Callable<ScadaCommandResult> {
        CommandLine cli;
        public AbstractScadaCommandCallable(CommandLine cli) {
            this.cli = cli;
        }
    }

    class ScanBinLabelCommand extends ScadaCommand {
        static final String FARM_ID = "farmid";
        static final String SIDE = "side";
        static final String LABEL = "label";
        static final String WEIGHT = "weight";

        public ScanBinLabelCommand() {
            super("Add new bin label to simulator");

            options.addOption(
                    OptionBuilder.withLongOpt(FARM_ID).withDescription("farm id").isRequired().hasArg().create());
            options.addOption(OptionBuilder.withLongOpt(SIDE).withDescription("side").isRequired().hasArg().create());
            options.addOption(
                    OptionBuilder.withLongOpt(LABEL).withDescription("bin label").isRequired().hasArg().create());
            options.addOption(OptionBuilder.withLongOpt(WEIGHT).withDescription("bin content weight").isRequired()
                    .hasArg().create());
        }

        @Override
        public AbstractScadaCommandCallable getCallable(CommandLine cli) {
            return new AbstractScadaCommandCallable(cli) {
                public ScadaCommandResult call() {
                    int farmId = Integer.parseInt(cli.getOptionValue(FARM_ID));
                    String side = cli.getOptionValue(SIDE);
                    String label = cli.getOptionValue(LABEL);
                    int weight = Integer.parseInt(cli.getOptionValue(WEIGHT));

                    SimulatorService simulator = (SimulatorService) context.getService(
                            ServiceContext.SIMULATOR_SERVICE);
                    simulator.scanBinLabel(farmId, side, label, weight);
                    return new ScadaCommandResult(0, "success");
                }
            };
        }
    }

    class ClearBinRoutingCommand extends ScadaCommand {
        public ClearBinRoutingCommand() {
            super("Clear ongoing bin routing information if any");
        }

        @Override
        public AbstractScadaCommandCallable getCallable(CommandLine cli) {
            return new AbstractScadaCommandCallable(cli) {
                public ScadaCommandResult call() {
                    SimulatorService simulator = (SimulatorService) context.getService(
                            ServiceContext.SIMULATOR_SERVICE);
                    simulator.clearBinRouting();
                    return new ScadaCommandResult(0, "success");
                }
            };
        }
    }

    Map<String, ScadaCommand> commands = new HashMap<String, ScadaCommand>();

    void initCommandOptions() {
        commands.put("scan_bin_label", new ScanBinLabelCommand());
        commands.put("clear_bin_routing", new ClearBinRoutingCommand());
    }

    class ScadaCommandFactory implements CommandFactory, Runnable {
        private ExecutorService executor = Executors.newFixedThreadPool(1);
        private ExitCallback exitCallback;
        private String[] args = null;
        private int argc = 0;

        ScadaCommandFactory() {
        }

        private void writeHelp(OutputStream out) throws IOException {
            for (Map.Entry<String, ScadaCommand> entry : commands.entrySet()) {
                String s = String.format("%s: %s\n", entry.getKey(), entry.getValue().getDescription());
                out.write(s.getBytes());
            }

            out.flush();
        }

        private void writeUsage(String name, ScadaCommand command) throws IOException {
            HelpFormatter fmt = new HelpFormatter();
            PrintWriter writer = new PrintWriter(err);
            fmt.printUsage(writer, 80, name, command.getOptions());
            writer.close();
            out.flush();
        }

        @Override
        public void run() {
            // logger.info(String.format("args: %s, argc: %d", Arrays.toString(args), argc));
            try {
                if (argc == 0 || args[0].equals("help")) {
                    writeHelp(out);
                    exitCallback.onExit(0, "");
                    return;
                }

                if (!commands.containsKey(args[0])) {
                    writeHelp(out);
                    exitCallback.onExit(-1, "command-not-found");
                    return;
                }

                ScadaCommand command = commands.get(args[0]);
                if (args.length > 1 && args[1].equals("help")) {
                    writeUsage(args[0], command);
                    exitCallback.onExit(0, "");
                    return;
                }

                BasicParser parser = new BasicParser();
                CommandLine cli = parser.parse(command.getOptions(), Arrays.copyOfRange(args, 1, args.length));
                AbstractScadaCommandCallable callable = command.getCallable(cli);
                try {
                    ScadaCommandResult res = callable.call();
                    exitCallback.onExit(res.getCode(), res.getMessage());
                } catch (Exception e) {
                    logger.info("failed-internal");
                    logger.info(e.getMessage());
                    e.printStackTrace();
                    exitCallback.onExit(-1, "failed-internal");
                }
                return;
            } catch (ParseException pe) {
                logger.info("failed-invalid-params");
                logger.info(pe.getMessage());
                exitCallback.onExit(-1, "failed-invalid-params");
            } catch (IOException ioe) {
                logger.info("failed-io");
                logger.info(ioe.getMessage());
                exitCallback.onExit(-1, "failed-io");
            }
        }

        private OutputStream out;
        private OutputStream err;
        public Command createCommand(ChannelSession channel, String command) throws IOException {
            logger.info(command);
            args = command.split(" ");
            argc = args.length;

            return new Command() {
                @Override
                public void setInputStream(InputStream in) {
                }

                @Override
                public void setOutputStream(OutputStream out) {
                    ScadaCommandFactory.this.out = out;
                }

                @Override
                public void setErrorStream(OutputStream err) {
                    ScadaCommandFactory.this.err = err;
                }

                @Override
                public void setExitCallback(ExitCallback callback) {
                    ScadaCommandFactory.this.exitCallback = callback;
                }

                @Override
                public void start(ChannelSession channel, Environment env) throws IOException {
                    executor.execute(ScadaCommandFactory.this);
                }

                @Override
                public void destroy(ChannelSession channel) {
                }
            };
        }
    }
}
