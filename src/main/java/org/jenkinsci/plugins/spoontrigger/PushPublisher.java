package org.jenkinsci.plugins.spoontrigger;

import com.google.common.base.Optional;
import com.google.common.reflect.TypeToken;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import lombok.Getter;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.spoontrigger.client.PushCommand;
import org.jenkinsci.plugins.spoontrigger.client.SpoonClient;
import org.jenkinsci.plugins.spoontrigger.hub.Image;
import org.jenkinsci.plugins.spoontrigger.push.PushConfig;
import org.jenkinsci.plugins.spoontrigger.push.RemoteImageNameStrategy;
import org.jenkinsci.plugins.spoontrigger.validation.Level;
import org.jenkinsci.plugins.spoontrigger.validation.StringValidators;
import org.jenkinsci.plugins.spoontrigger.validation.Validator;
import org.jenkinsci.plugins.spoontrigger.validation.Validators;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nullable;

import static org.jenkinsci.plugins.spoontrigger.Messages.*;

/**
 * Class is kept for backwards compatibility with existing Jenkins build projects.
 *
 * @deprecated use {@link PushBuilder} instead.
 */
@Deprecated
public class PushPublisher extends SpoonBasePublisher {
    @Nullable
    @Getter
    private final String remoteImageName;
    @Nullable
    @Getter
    private final String dateFormat;
    @Nullable
    @Getter
    private final String organization;

    @Getter
    private final RemoteImageNameStrategy remoteImageStrategy;
    @Getter
    private final boolean appendDate;
    @Getter
    private final boolean overwriteOrganization;

    private transient PushConfig pushConfig;

    @DataBoundConstructor
    public PushPublisher(@Nullable RemoteImageNameStrategy remoteImageStrategy,
                         @Nullable String organization, boolean overwriteOrganization,
                         @Nullable String remoteImageName, @Nullable String dateFormat, boolean appendDate) {
        this.remoteImageStrategy = (remoteImageStrategy == null) ? RemoteImageNameStrategy.DO_NOT_USE : remoteImageStrategy;
        this.organization = Util.fixEmptyAndTrim(organization);
        this.overwriteOrganization = overwriteOrganization;
        this.remoteImageName = Util.fixEmptyAndTrim(remoteImageName);
        this.dateFormat = Util.fixEmptyAndTrim(dateFormat);
        this.appendDate = appendDate;
    }

    @Override
    public void beforePublish(SpoonBuild build, BuildListener listener) {
        super.beforePublish(build, listener);

        this.remoteImageStrategy.validate(getPushConfig(), build);
    }

    @Override
    public void publish(AbstractBuild<?, ?> abstractBuild, Launcher launcher, BuildListener listener) throws IllegalStateException {
        SpoonBuild build = (SpoonBuild) abstractBuild;
        SpoonClient client = super.createClient(build, launcher, listener);
        PushCommand pushCmd = this.createPushCommand(build);
        pushCmd.run(client);
    }

    private PushCommand createPushCommand(SpoonBuild spoonBuild) {
        PushCommand.CommandBuilder cmdBuilder = PushCommand.builder().image(super.getImage().get().printIdentifier());

        Optional<Image> remoteImage = this.remoteImageStrategy.tryGetRemoteImage(getPushConfig(), spoonBuild);
        if (remoteImage.isPresent()) {
            cmdBuilder.remoteImage(remoteImage.get().printIdentifier());
        }

        return cmdBuilder.build();
    }

    private PushConfig getPushConfig() {
        if (pushConfig == null) {
            pushConfig = new PushConfig(remoteImageName, dateFormat, appendDate, organization, overwriteOrganization);
        }
        return pushConfig;
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private static final Validator<String> REMOTE_IMAGE_NAME_VALIDATOR;
        private static final Validator<String> ORGANIZATION_VALIDATOR;
        private static final Validator<String> DATE_FORMAT_VALIDATOR;

        static {
            REMOTE_IMAGE_NAME_VALIDATOR = Validators.chain(
                    StringValidators.isNotNull(REQUIRED_PARAMETER, Level.ERROR),
                    StringValidators.isSingleWord(String.format(REQUIRE_SINGLE_WORD_S, "Parameter")));

            ORGANIZATION_VALIDATOR = Validators.chain(
                    StringValidators.isNotNull(IGNORE_PARAMETER, Level.OK),
                    StringValidators.isSingleWord(String.format(REQUIRE_SINGLE_WORD_S, "Organization")));

            DATE_FORMAT_VALIDATOR = Validators.chain(
                    StringValidators.isNotNull(IGNORE_PARAMETER, Level.OK),
                    StringValidators.isSingleWord(String.format(REQUIRE_SINGLE_WORD_S, "Date format")),
                    StringValidators.isDateFormat(INVALID_DATE_FORMAT));
        }

        private static String getKeyOrDefault(JSONObject json, String key) {
            return json.containsKey(key) ? json.getString(key) : null;
        }

        private static boolean getBoolOrDefault(JSONObject json, String key) {
            return json.containsKey(key) && json.getBoolean(key);
        }

        @Override
        public Publisher newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            try {
                JSONObject pushJSON = formData.getJSONObject("remoteImageStrategy");

                RemoteImageNameStrategy remoteImageStrategy = RemoteImageNameStrategy.DO_NOT_USE;
                String remoteImageName = null;
                String dateFormat = null;
                boolean appendDate = false;
                String organization = null;
                boolean overwriteOrganization = false;

                if (pushJSON != null && !pushJSON.isNullObject()) {
                    String remoteImageStrategyName = pushJSON.getString("value");
                    remoteImageStrategy = RemoteImageNameStrategy.valueOf(remoteImageStrategyName);
                    organization = getKeyOrDefault(pushJSON, "organization");
                    overwriteOrganization = getBoolOrDefault(pushJSON, "overwriteOrganization");
                    remoteImageName = getKeyOrDefault(pushJSON, "remoteImageName");
                    dateFormat = getKeyOrDefault(pushJSON, "dateFormat");
                    appendDate = getBoolOrDefault(pushJSON, "appendDate");
                }

                return new PushPublisher(remoteImageStrategy, organization, overwriteOrganization, remoteImageName, dateFormat, appendDate);
            } catch (JSONException ex) {
                throw new IllegalStateException("Error while parsing data form", ex);
            }
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return TypeToken.of(SpoonProject.class).isAssignableFrom(aClass);
        }

        public FormValidation doCheckRemoteImageName(@QueryParameter String value) {
            String imageName = Util.fixEmptyAndTrim(value);
            return Validators.validate(REMOTE_IMAGE_NAME_VALIDATOR, imageName);
        }

        public FormValidation doCheckDateFormat(@QueryParameter String value) {
            String dateFormat = Util.fixEmptyAndTrim(value);
            return Validators.validate(DATE_FORMAT_VALIDATOR, dateFormat);
        }

        public FormValidation doCheckOrganization(@QueryParameter String value) {
            String organization = Util.fixEmptyAndTrim(value);
            return Validators.validate(ORGANIZATION_VALIDATOR, organization);
        }

        @Override
        public String getDisplayName() {
            return "Push Turbo image";
        }
    }
}
