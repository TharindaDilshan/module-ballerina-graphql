/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.stdlib.graphql.runtime.engine;

import io.ballerina.runtime.api.Environment;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.ResourceMethodType;
import io.ballerina.runtime.api.types.ServiceType;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.stdlib.graphql.runtime.schema.Schema;
import io.ballerina.stdlib.graphql.runtime.schema.tree.SchemaGenerator;

import java.util.concurrent.CountDownLatch;

import static io.ballerina.stdlib.graphql.runtime.engine.CallableUnitCallback.getDataFromArray;
import static io.ballerina.stdlib.graphql.runtime.engine.CallableUnitCallback.getDataFromRecord;
import static io.ballerina.stdlib.graphql.runtime.engine.CallableUnitCallback.getDataFromService;
import static io.ballerina.stdlib.graphql.runtime.engine.EngineUtils.ARGUMENTS_FIELD;
import static io.ballerina.stdlib.graphql.runtime.engine.EngineUtils.NAME_FIELD;
import static io.ballerina.stdlib.graphql.runtime.engine.EngineUtils.VALUE_FIELD;
import static io.ballerina.stdlib.graphql.runtime.engine.EngineUtils.getResourceName;
import static io.ballerina.stdlib.graphql.runtime.engine.EngineUtils.getSchemaRecordFromSchema;
import static io.ballerina.stdlib.graphql.runtime.engine.IntrospectionUtils.initializeIntrospectionTypes;
import static io.ballerina.stdlib.graphql.runtime.utils.Utils.STRAND_METADATA;

/**
 * This handles Ballerina GraphQL Engine.
 */
public class Engine {

    public static Object createSchema(BObject service) {
        try {
            ServiceType serviceType = (ServiceType) service.getType();
            Schema schema = createSchema(serviceType);
            initializeIntrospectionTypes(schema);
            return getSchemaRecordFromSchema(schema);
        } catch (BError e) {
            return e;
        }
    }

    private static Schema createSchema(ServiceType serviceType) {
        SchemaGenerator schemaGenerator = new SchemaGenerator(serviceType);
        return schemaGenerator.generate();
    }

    public static Object executeResource(Environment environment, BObject service, BObject visitor, BObject fieldNode) {
        ServiceType serviceType = (ServiceType) service.getType();
        String fieldName = fieldNode.getStringValue(NAME_FIELD).getValue();
        for (ResourceMethodType resourceMethod : serviceType.getResourceMethods()) {
            String resourceName = getResourceName(resourceMethod);
            if (resourceName.equals(fieldName)) {
                return getResourceExecutionResult(environment, service, visitor, fieldNode, resourceMethod);
            }
        }
        // Won't hit here if the exact resource is already found, hence must be hierarchical resource
        return getDataFromService(environment, service, visitor, fieldNode);
    }

    private static Object getResourceExecutionResult(Environment environment, BObject service, BObject visitor,
                                                     BObject fieldNode, ResourceMethodType resourceMethod) {

        BMap<BString, Object> arguments = getArgumentsFromField(fieldNode);
        Object[] args = getArgsForResource(resourceMethod, arguments);
        CountDownLatch latch = new CountDownLatch(1);
        CallableUnitCallback callback = new CallableUnitCallback(environment, latch, visitor, fieldNode);
        environment.getRuntime().invokeMethodAsync(service, resourceMethod.getName(), null, STRAND_METADATA,
                                                   callback, args);
        try {
            latch.await();
        } catch (InterruptedException e) {
            // Ignore
        }
        return callback.getResult();
    }

    public static Object getDataFromBalType(BObject fieldNode, Object data) {
        if (data instanceof BArray) {
            return getDataFromArray(fieldNode, (BArray) data);
        } else if (data instanceof BMap) {
            return getDataFromRecord(fieldNode, (BMap<BString, Object>) data);
        } else {
            return data;
        }
    }

    private static BMap<BString, Object> getArgumentsFromField(BObject fieldNode) {
        BArray argumentArray = fieldNode.getArrayValue(ARGUMENTS_FIELD);
        BMap<BString, Object> argumentsMap = ValueCreator.createMapValue();
        for (int i = 0; i < argumentArray.size(); i++) {
            BObject argumentNode = (BObject) argumentArray.get(i);
            BMap<BString, Object> argNameRecord = (BMap<BString, Object>) argumentNode.getMapValue(NAME_FIELD);
            BMap<BString, Object> argValueRecord = (BMap<BString, Object>) argumentNode.getMapValue(VALUE_FIELD);
            BString argName = argNameRecord.getStringValue(VALUE_FIELD);
            Object argValue = argValueRecord.get(VALUE_FIELD);
            argumentsMap.put(argName, argValue);
        }
        return argumentsMap;
    }

    private static Object[] getArgsForResource(ResourceMethodType resourceMethod, BMap<BString, Object> arguments) {
        String[] paramNames = resourceMethod.getParamNames();
        Object[] result = new Object[paramNames.length * 2];
        for (int i = 0, j = 0; i < paramNames.length; i += 1, j += 2) {
            result[j] = arguments.get(StringUtils.fromString(paramNames[i]));
            result[j + 1] = true;
        }
        return result;
    }
}
