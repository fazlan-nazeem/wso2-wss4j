/*
 * Copyright 2004,2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ws.security.policy;

import java.util.ArrayList;

/**
 * 
 * This class holds data and parameters for a supporting token. 
 * 
 * The data is not declared as private to provide direct access from
 * other classes in this package.
 * 
 * @author Werner Dittmann (werner@apache.org)
 */

public class WSS4JSupportingToken {
    int tokenType;
    
    ArrayList supportTokens;
    
    ArrayList sigParts;

    ArrayList sigElements;

    ArrayList encParts;

    ArrayList encElements;
}