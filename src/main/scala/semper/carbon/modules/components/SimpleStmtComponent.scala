/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package semper.carbon.modules.components

import semper.sil.{ast => sil}
import semper.carbon.boogie.{Statements, Stmt}

/**
 * A statement component that only contributes at the end.

 */
trait SimpleStmtComponent extends StmtComponent {

  /**
   * Potentially contributes to the translation of a statement.  If no contribution
   * is desired, then [[semper.carbon.boogie.Statements.EmptyStmt]] can be used as a
   * return value.
   */
  def simpleHandleStmt(s: sil.Stmt): Stmt

  override def handleStmt(s: sil.Stmt) = (Statements.EmptyStmt, simpleHandleStmt(s))
}
