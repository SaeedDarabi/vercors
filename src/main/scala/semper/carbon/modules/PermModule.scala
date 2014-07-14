/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package semper.carbon.modules

import semper.carbon.boogie._
import semper.sil.{ast => sil}

/**
 * The permission module determines the encoding of permissions and allows to add or remove
 * permission.

 */
trait PermModule extends Module {

  /**
   * The type used to represent permissions.
   */
  def permType: Type

  /**
   * Translate a permission amount
   */
  def translatePerm(e: sil.Exp): Exp

  /**
   * Translate a permission comparison
   */
  def translatePermComparison(e: sil.Exp): Exp

  /**
   * The number of phases during exhale.
   */
  def numberOfPhases: Int

  /**
   * The ID of the phase that this expression should be exhaled in.
   */
  def isInPhase(e: sil.Exp, phaseId: Int): Boolean

  /**
   * A short description of a given phase.
   */
  def phaseDescription(phase: Int): String

  /**
   * The current mask.
   */
  def currentMask: Seq[Exp]

  /**
   * A static reference to the mask.
   */
  def staticMask: Seq[LocalVarDecl]

  /**
   * Is the permission for a given expression positive (using the static mask).
   */
  def staticPermissionPositive(rcv: Exp, loc: Exp): Exp

  /**
   * The predicate mask field of a given predicate (as its ghost location).
   */
  def predicateMaskField(pred: Exp): Exp

  /**
   * The type used to for predicate masks.
   */
  def pmaskType: Type

  def zeroPMask: Exp

  def hasDirectPerm(la: sil.LocationAccess): Exp
}
