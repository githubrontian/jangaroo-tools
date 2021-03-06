/*
 * Copyright 2008 CoreMedia AG
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 
 * 
 * Unless required by applicable law or agreed to in writing, 
 * software distributed under the License is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either 
 * express or implied. See the License for the specific language 
 * governing permissions and limitations under the License.
 */

package net.jangaroo.jooc.ast;

import net.jangaroo.jooc.Scope;

import java.util.List;

/**
 * Statements are language elements that perform or specify an action at runtime.
 *
 * @author Andreas Gawecki
 */
public abstract class Statement extends Directive {
  private Scope scope;

  @Override
  public void analyze(AstNode parentNode) {
    super.analyze(parentNode);

    if (!(this instanceof Declaration) && parentNode instanceof ClassBody && scope != null) {
      CompilationUnit compilationUnit = scope.getCompilationUnit();
      compilationUnit.setHasStaticCode();
    }
  }

  @Override
  public <N extends AstNode> void scope(List<N> nodes, Scope scope) {
    super.scope(nodes, scope);
    this.scope = scope;
  }
}
