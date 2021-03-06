package io.jenkins.plugins;

import hudson.Extension;
import hudson.EnvVars;
import hudson.model.AbstractBuild;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import okhttp3.*;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.annotation.Nonnull;

import org.apache.commons.io.IOUtils;
import org.apache.commons.text.StringSubstitutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
public class JobListener extends RunListener<AbstractBuild> {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private OkHttpClient client;

    private static final Logger log = LoggerFactory.getLogger(JobListener.class);

    public JobListener() {
        super(AbstractBuild.class);
        client = new OkHttpClient();
    }

    @Override
    public void onCompleted(AbstractBuild build, @Nonnull TaskListener listener) {
        WebHookPublisher publisher = GetWebHookPublisher(build);
        if (publisher == null) {
            return;
        }

        Result result = build.getResult();
        if (result == null) {
            return;
        }

        PrintStream buildConsole = listener.getLogger();

        // Send the webhook on successful deploys. UNSTABLE could be successful depending on how the pipeline is set up
        if (result.equals(Result.SUCCESS) || result.equals(Result.UNSTABLE)) {
            try {
                JsonObject payload = buildDeployPayload(publisher, build, listener);
                String webHookUrl = publisher.webHookUrl;
                buildConsole.print("Publishing deploy to OpsLevel via: " + webHookUrl + "\n");
                httpPost(webHookUrl, payload, buildConsole);
            }
            catch(Exception e) {
                String message = e.toString() + ". Could not publish deploy to OpsLevel.\n";
                log.error(message);
                buildConsole.print("Error :" + message);
            }
        }

    }

    private WebHookPublisher GetWebHookPublisher(AbstractBuild build) {
        for (Object publisher : build.getProject().getPublishersList().toMap().values()) {
            if (publisher instanceof WebHookPublisher) {
                return (WebHookPublisher) publisher;
            }
        }
        return null;
    }

    private void httpPost(String webHookUrl, JsonObject payload, PrintStream buildConsole) throws IOException {
        // Get the plugin version to pass through as a request parameter
        final Properties properties = new Properties();
        String version = "";
        // Have to catch the potential IO Exception
        try {
            // TODO: In development this seems to pull from src/main/config.properties, instead of target/classes/properties
            //       Once the plugin is compiled it will get the correct version string, but we could not figure out how
            //       to get it looking at the right place in development
            properties.load(getClass().getClassLoader().getResourceAsStream("config.properties"));
            version = properties.getProperty("plugin.version");
        }
        catch (Exception e) {
            log.error("Project properties does not exist. {}", e.toString());
        }
        String agent = "jenkins-" + version;

        // Build the URL with query params
        HttpUrl.Builder httpBuilder = HttpUrl.parse(webHookUrl).newBuilder();
        HttpUrl url = httpBuilder
            .addQueryParameter("agent", agent)
            .build();

        // Build the body
        String jsonString = payload.toString();
        log.info("Sending OpsLevel Integration payload:\n{}", jsonString);

        RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, jsonString);

        // Finally, put the request together
        Request request = new Request.Builder()
            .url(url)
            .post(body)
            .build();

        try {
            Response response = client.newCall(request).execute();
            log.info("Invocation of webhook {} successful", url);
            ResponseBody responseBody = response.body();
            if (responseBody != null) {
                String message = "Response: " + responseBody.string() + "\n";
                buildConsole.print(message);
                log.info(message);
            }
        } catch (Exception e) {
            log.info("Invocation of webhook {} failed: {}", url, e.toString());
            throw e;
        }
    }

    private JsonObject buildDeployPayload(WebHookPublisher publisher, AbstractBuild build, TaskListener listener) throws InterruptedException, IOException {
        // Leaving a sample payload here for visibility while developing.
        // {
        //     "dedup_id": "9ae54794-dfc5-4ac8-b1b5-78789f20f3f8",
        //     "service": "shopping_cart",                            // CAN OVERRIDE
        //     "deployer": {
        //       "id": "1a9f841f-9a3d-4423-a05a-7e9c31a02b16",        // CAN OVERRIDE
        //       "email": "mscott@example.com",                       // CAN OVERRIDE
        //       "name": "Michael Scott"                              // CAN OVERRIDE
        //     },
        //     "deployed_at": "'"$(date -u '+%FT%TZ')"'",
        //     "environment": "Production",                           // CAN OVERRIDE
        //     "description": "Deployed by CI Pipeline: Deploy #234", // CAN OVERRIDE - needs var subs
        //     "deploy_url": "https://heroku.deploys.com",            // CAN OVERRIDE - needs var subss
        //     "deploy_number": "234",
        //     "commit": {
        //       "sha": "38d02f1d7aab64678a7ad3eeb2ad2887ce7253f5",
        //       "message": "Merge branch 'fix-tax-rate' into 'master'",
        //       "branch": "master",
        //       "date": "'"$(date -u '+%FT%TZ')"'",
        //       "committer_name": "Michael Scott",
        //       "committer_email": "mscott@example.com",
        //       "author_name": "Michael Scott",
        //       "author_email": "mscott@example.com",
        //       "authoring_date": "'"$(date -u '+%FT%TZ')"'"
        //     }

        EnvVars env = build.getEnvironment(listener);

        // TODO: remove debugging: Printing env variables
        for (String key : env.keySet()) {
            log.info(key + ": " + env.get(key));
        }

        // Default to UUID. Perhaps allow this to be set with envVars ${JOB_NAME}_${BUILD_ID} / ${BUILD_TAG}
        String dedupId = UUID.randomUUID().toString();

        // It didn't make sense to allow overriding deploy number. Use the value from Jenkin
        String deployNumber = env.get("BUILD_NUMBER");

        // URL of the asset that was just deployed
        String deployUrl = stringSub(publisher.deployUrl, env);
        if (deployUrl == null) {
            deployUrl = getDeployUrl(build);
        }

        // ISO datetime with no milliseconds
        DateTimeFormatter dtf = DateTimeFormatter.ISO_INSTANT;
        String deployedAt = ZonedDateTime.now().format(dtf);

        // Typically Test/Staging/Production
        String environment = stringSub(publisher.environment, env);
        if (environment == null) {
            environment = "Production";
        }

        // Conform to kubernetes conventions with this prefix
        String service = "jenkins:" + env.get("JOB_NAME");
        if(publisher.serviceAlias != null) {
            service = stringSub(publisher.serviceAlias, env);
        }

        // Details of who deployed, if available
        JsonObject deployerJson = buildDeployerJson(publisher, env);

        // Details of the commit, if available
        JsonObject commitJson = buildCommitJson(env);

        // Description that is hopefully meaningful
        String description = stringSub(publisher.description, env);
        if (description == null) {
            if (commitJson != null && commitJson.containsKey("message")) {
                description = commitJson.getString("message");
            } else {
                description = stringSub("Jenkins Deploy #${BUILD_NUMBER}", env);
            }
        }

        JsonObjectBuilder payload = Json.createObjectBuilder();
        payload.add("dedup_id", dedupId);
        payload.add("deploy_number", deployNumber);
        payload.add("deploy_url", deployUrl);
        payload.add("deployed_at", deployedAt);
        payload.add("description", description);
        payload.add("environment", environment);
        payload.add("service", service);

        if (deployerJson != null) {
            payload.add("deployer", deployerJson);
        }

        if (commitJson != null) {
            payload.add("commit", commitJson);
        }

        return payload.build();
    }

    private String stringSub(String templateString, EnvVars env) {
        StringSubstitutor sub = new StringSubstitutor(env);
        return sub.replace(templateString);
    }

    private String getDeployUrl(AbstractBuild build) {
        try {
            // Full URL, if Jenkins Location is set (on /configure page)
            // By default the UI shows http://localhost:8080/jenkins/
            // but the actual value is unset so this function throws an exception
            return build.getAbsoluteUrl();
        }
        catch(java.lang.IllegalStateException e) {
            // build.getUrl() always works but returns a relative path
            return "http://jenkins-location-is-not-set.local/" + build.getUrl();
        }
    }

    private JsonObject buildDeployerJson(WebHookPublisher publisher, EnvVars env) {
        // TODO: how to access the user who triggered this build?
        String deployerId = publisher.deployerId;
        String deployerName = publisher.deployerName;
        String deployerEmail = publisher.deployerEmail;

        if (deployerId == null && deployerName == null && deployerEmail == null) {
            return null;
        }

        JsonObjectBuilder deployer = Json.createObjectBuilder();

        if (deployerId != null) {
            deployer.add("id", stringSub(deployerId, env));
        }

        if (deployerName != null) {
            deployer.add("name", stringSub(deployerName, env));
        }

        if (deployerEmail != null) {
            deployer.add("email", stringSub(deployerEmail, env));
        }

        return deployer.build();
    }

    private JsonObject buildCommitJson(EnvVars env) {
        String commitHash = env.get("GIT_COMMIT");
        if (commitHash == null) {
            // This build doesn't use git
            return null;
        }
        JsonObjectBuilder commitJson = Json.createObjectBuilder();
        commitJson.add("sha", commitHash);

        String commitBranch = env.get("GIT_BRANCH");
        if (commitBranch != null) {
            commitJson.add("branch", commitBranch);
        }

        String commitMessage = getGitCommitMessage(env);
        if (commitMessage != null) {
            commitJson.add("message", commitMessage);
        }

        return commitJson.build();
    }

    private String getGitCommitMessage(EnvVars env) {
        String output = execCmd(env, "git", "show", "--pretty=%s");
        String[] result = output.split(System.lineSeparator(), 2);
        return result[0];
    }

    private static String execCmd(EnvVars env, String... cmd) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(env.get("WORKSPACE")));
        Process p = null;
        try {
            p = pb.start();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        int exitCode = 0;
        try {
            exitCode = p.waitFor();
        } catch (InterruptedException e) {
            e.printStackTrace();
            return null;
        }
        if (exitCode != 0) {
            String stderr = null;
            try {
                stderr = IOUtils.toString(p.getErrorStream(), Charset.defaultCharset());
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
            String strCmd = String.join(" ", cmd);
            log.warn("Failed to execute command: {}. Exit code: {}. Stderr:: {}", strCmd, exitCode, stderr);
        }

        try {
            String stdout = IOUtils.toString(p.getInputStream(), Charset.defaultCharset());
            return stdout;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}
