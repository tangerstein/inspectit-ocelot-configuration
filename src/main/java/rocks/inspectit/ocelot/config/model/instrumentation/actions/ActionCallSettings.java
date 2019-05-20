package rocks.inspectit.ocelot.config.model.instrumentation.actions;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.val;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.convert.ConversionService;
import rocks.inspectit.ocelot.config.model.instrumentation.InstrumentationSettings;
import rocks.inspectit.ocelot.config.utils.AutoboxingHelper;
import rocks.inspectit.ocelot.config.utils.ConfigUtils;
import rocks.inspectit.ocelot.config.validation.ViolationBuilder;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Stream;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ActionCallSettings extends ConditionalActionSettings {


    /**
     * The name of the action.
     * References the defined actions in {@link InstrumentationSettings#getActions()}.
     */
    @NotBlank
    private String action;

    /**
     * Defines input values for the action using constants, e.g. numbers or string literals.
     * The key of the map denotes the input variable of the action, the value is the constant value to assign.
     * The given string literals are converted to the corresponding primitive type or directly used as a string.
     * Constant input only works for inputs which have a primitive type, a corresponding wrapper type or are strings.
     */
    private Map<@NotBlank String, Object> constantInput = Collections.emptyMap();

    /**
     * Defines input values for the action based on data from the context.
     * The key of the map denotes the input variable of the action, the value is the name of the data to
     * extract from the context as value.
     * Here no configuration time type checks are possible, if the types do not match the action will fail with a classcast exception.
     * In this case the action will not be executed anymore for the given method.
     */
    private Map<@NotBlank String, @NotBlank String> dataInput = Collections.emptyMap();

    /**
     * Normally, we assume references to data keys implicitly define a dependency.
     * For example, when defining data-input: { x: my_data} we assume that this action uses the value of the data key
     * my_data to populate the action parameter "x".
     * We then assume that if any action on the same method within the same entry / exit section writes "my_data" it has to be executed before.
     * This however is not always the case: in some cases we want to read a down-propagated value before it is overridden.
     * <p>
     * This map serves this purpose: it defines a set of data_keys.
     * The action is now scheduled in a way that it is executed before any data-key mentioned in this set is written within
     * the same method entry or exit phase
     */
    private Map<@NotBlank String, @NotNull Boolean> before = Collections.emptyMap();

    public void performValidation(InstrumentationSettings container, ViolationBuilder vios) {
        val actionConf = container.getActions().get(action);
        if (actionConf == null) {
            vios.message("Action with name '{action}' does not exist!")
                    .parameter("action", action)
                    .buildAndPublish();
        } else {
            checkAllInputsAssigned(actionConf, vios);
            checkNoDuplicateAssignments(vios);
            checkNoNonExistingInputsAssigned(actionConf, vios);
            checkNoSpecialInputsAssigned(vios);
            checkConstantInputsCanBeDecoded(actionConf, vios);
        }
    }

    private void checkNoDuplicateAssignments(ViolationBuilder vios) {
        Stream.concat(constantInput.keySet().stream(), dataInput.keySet().stream())
                .distinct()
                .filter(constantInput::containsKey)
                .filter(dataInput::containsKey)
                .forEach(varName -> vios
                        .message("Multiple assignments are given to same variable '{var}'!")
                        .parameter("var", varName)
                        .buildAndPublish());
    }

    private void checkAllInputsAssigned(GenericActionSettings actionConf, ViolationBuilder vios) {
        actionConf.getInput().keySet().stream()
                .filter(varName -> !GenericActionSettings.isSpecialVariable(varName))
                .filter(varName -> !constantInput.containsKey(varName))
                .filter(varName -> !dataInput.containsKey(varName))
                .forEach(varName -> vios
                        .message("Parameter '{var}' of action '{action}' is not assigned!")
                        .parameter("var", varName)
                        .parameter("action", action)
                        .buildAndPublish());
    }

    private void checkNoSpecialInputsAssigned(ViolationBuilder vios) {
        Stream.concat(constantInput.keySet().stream(), dataInput.keySet().stream())
                .filter(GenericActionSettings::isSpecialVariable)
                .forEach(varName -> vios
                        .message("Assigned parameter '{var}' is a special variable and must not be assigned manually!")
                        .parameter("var", varName)
                        .buildAndPublish());
    }

    private void checkNoNonExistingInputsAssigned(GenericActionSettings actionConf, ViolationBuilder vios) {
        Stream.concat(constantInput.keySet().stream(), dataInput.keySet().stream())
                .filter(varName -> !actionConf.getInput().containsKey(varName))
                .forEach(varName -> vios
                        .message("Assigned parameter '{var}' does not exist as input for action '{action}'!")
                        .parameter("var", varName)
                        .parameter("action", action)
                        .buildAndPublish());
    }

    private void checkConstantInputsCanBeDecoded(GenericActionSettings actionConf, ViolationBuilder vios) {
        constantInput.forEach((varName, value) -> {
            String type = actionConf.getInput().get(varName);
            if (type != null) {
                if (value == null) {
                    if (AutoboxingHelper.isPrimitiveType(type)) {
                        vios.message("The input '{var}' of '{action}' cannot be 'null' as it is a primitive ('{type}')!")
                                .parameter("var", varName)
                                .parameter("action", action)
                                .parameter("type", type)
                                .buildAndPublish();
                    }
                } else {
                    checkConstantAssignmentForNonNullValue(actionConf, vios, varName, value, type);
                }
            }
        });
    }

    private void checkConstantAssignmentForNonNullValue(GenericActionSettings actionConf, ViolationBuilder vios, @NotBlank String varName, Object value, String type) {
        Class<?> typeClass;
        if (AutoboxingHelper.isPrimitiveType(type) || AutoboxingHelper.isWrapperType(type)) {
            typeClass = AutoboxingHelper.getWrapperClass(type);
            if (value instanceof String && ((String) value).isEmpty()) {
                vios.message("The input '{var}' of '{action}' cannot be empty as it is a primitive or wrapper type ('{type}')!")
                        .parameter("var", varName)
                        .parameter("action", action)
                        .parameter("type", type)
                        .buildAndPublish();
            }
        } else {
            typeClass = ConfigUtils.locateTypeWithinImports(type, null, actionConf.getImports());
        }
        if (typeClass == null) {
            vios.message("The input '{var}' of '{action}' cannot be specified as a non-null constant value, as it has the unknown type '{type}'!")
                    .parameter("var", varName)
                    .parameter("action", action)
                    .parameter("type", type)
                    .buildAndPublish();
        } else {
            try {
                getConstantInputAsType(varName, typeClass);
            } catch (Exception e) {
                vios.message("The given value '{value}' for input '{var}' of '{action}' cannot be converted to '{type}': {errorMsg}")
                        .parameter("value", value)
                        .parameter("var", varName)
                        .parameter("action", action)
                        .parameter("type", type)
                        .parameter("errorMsg", e.getMessage())
                        .buildAndPublish();
            }
        }
    }

    public <T> T getConstantInputAsType(String variable, Class<T> targetType) throws ConversionException {
        Object value = constantInput.get(variable);
        if (value == null) {
            return null;
        } else {
            ConversionService conversionService = ApplicationConversionService.getSharedInstance();
            if (!conversionService.canConvert(value.getClass(), targetType)) {
                throw new IllegalArgumentException("Cannot parse '" + targetType.getName() + "' from a '" + value.getClass() + "'!");
            }
            return conversionService.convert(value, targetType);
        }
    }

}
