package org.mmisw.udunits2rdf

import com.hp.hpl.jena.rdf.model.{Property, Resource, ModelFactory, Model}
import com.hp.hpl.jena.vocabulary.{RDFS, OWL, RDF}
import org.apache.commons.codec.digest.DigestUtils

import scala.xml.{Text, Node}
import scala.collection.mutable

/**
 * Base converter class for the unit and prefix subclasses.
 *
 * @param xmlIn       Input XML
 * @param namespace   Namespace for the generated ontology
 */
abstract class Converter(xmlIn: Node, baseDefs: BaseDefs, namespace: String) {
  require(namespace.matches(".*(/|#)$"), "namespace must end with / or #")

  protected val UnitClass      = baseDefs.UnitClass
  protected val UnitNameClass  = baseDefs.UnitNameClass
  protected val PrefixClass    = baseDefs.PrefixClass

  protected val hasDefinition  = baseDefs.hasDefinition
  protected val hasName        = baseDefs.hasName
  protected val hasAlias       = baseDefs.hasAlias
  protected val hasSymbol      = baseDefs.hasSymbol

  protected val hasCardinality = baseDefs.hasCardinality
  protected val namesUnit      = baseDefs.namesUnit

  protected val hasValue       = baseDefs.hasValue

  def convert: Model

  def getStats: String

  protected def createModel: Model = {
    val model = ModelFactory.createDefaultModel()
    model.setNsPrefix("",      namespace)
    model.setNsPrefix("rdfs",  RDFS.getURI)
    model.setNsPrefix("owl",   OWL.getURI)
    model
  }

  protected val model = createModel

  if (namespace != baseDefs.namespace) model.setNsPrefix("u2", baseDefs.namespace)

  // but add all baseDefs statements as well; immediate goal is get ORR to show
  // the "synopsis of ontology contents" (otherwise that section would be empty);
  // but it doesn't hurt to make the vocabulary itself more self-contained
  model.add(baseDefs.model)

  protected def createResource(name: String): Resource = {
    model.createResource(namespace + name)
  }

  protected def createProperty(name: String): Property = {
    model.createProperty(namespace + name)
  }

  protected def createClass(name: String): Resource = {
    val clazz  = createResource(name)
    model.add(model.createStatement(clazz, RDF.`type`, OWL.Class))
    model.add(model.createStatement(clazz, RDFS.label, name))
    clazz
  }
}

/**
 * UDUnits to RDF converter.
 */
class UnitConverter(xmlIn: Node, baseDefs: BaseDefs, namespace: String) extends
      Converter(xmlIn: Node, baseDefs: BaseDefs, namespace: String) {

  private object stats {
    var unitsInInput = 0
    var unitsInOutput = 0
    var unitNamesInOutput = 0
    var unitsWithNoDef = 0
    var unitsWithMultipleDefs = 0
    var unitsWithMultipleSingularNames = 0
    var unitsWithMultiplePluralNames = 0

    var warnings = List[String]()

    def addWarning(s: String)  { warnings = s :: warnings}

    override def toString =
      s"""  unitsInInput                    = $unitsInInput
         |  unitsInOutput                   = $unitsInOutput
         |  unitNamesInOutput               = $unitNamesInOutput
         |  unitsWithNoDef                  = $unitsWithNoDef
         |  unitsWithMultipleDefs           = $unitsWithMultipleDefs
         |  unitsWithMultipleSingularNames  = $unitsWithMultipleSingularNames
         |  unitsWithMultiplePluralNames    = $unitsWithMultiplePluralNames
         |
         |${warnings.reverse.mkString("  ", "\n  ", "")}
       """.stripMargin
  }

  // index by corresponding def
  private val unitInstances = mutable.Map[String, Resource]()

  private def getUnitInstance(_def: String, unit: Node): Resource = {
    if (unitInstances.contains(_def)) {
      val unitInstance = unitInstances.get(_def).head
      stats.addWarning(s"warning: repeated def='${_def}'")
      unitInstance
    }
    else {
      val id = "_" + DigestUtils.sha1Hex(_def).substring(0, 8)
      val uri = namespace + id
      val unitInstance = model.createResource(uri, UnitClass)
      model.add(model.createStatement(unitInstance, RDF.`type`, UnitClass))
      unitInstance.addProperty(hasDefinition, _def)
      unitInstances.update(_def, unitInstance)
      unitInstance
    }
  }

  private def convertUnit(unit: Node) {
    val defs = (unit \ "def").map(_.text).filter(_.length > 0)
    if (defs.length == 1) {
      createUnitInstance(defs.head, unit)
    }
    else if (defs.length == 0) {
      stats.unitsWithNoDef += 1
    }
    else if (defs.length > 1) {
      stats.unitsWithMultipleDefs += 1
    }
  }

  def convert: Model = {
    for (unit <- xmlIn \ "unit") {
      stats.unitsInInput += 1
      convertUnit(scala.xml.Utility.trim(unit))
    }
    model
  }

  def getStats = stats.toString

  private def createUnitInstance(_def: String, unit: Node): Resource = {

    val unitInstance = getUnitInstance(_def, unit)

    // names at first level  (aliases are handled below):

    val singulars = (unit \ "name" \ "singular").map(_.text).filter(_.length > 0)
    singulars.headOption foreach { name =>
      //
      // first singular name, if any, will be the primary name
      //
      val nameInstance = createUnitNameInstance(name, "singular", false, unitInstance, Some(unit))
      model.add(model.createStatement(unitInstance, hasName, nameInstance))
    }

    if (singulars.length > 1) {
      println(s"\nwithMultipleSingularNames ${_def}: $singulars")
      stats.unitsWithMultipleSingularNames += 1
    }

    val plurals = (unit \ "name" \ "plural").map(_.text).filter(_.length > 0)
    if (plurals.length >= 1) {
      if (singulars.length == 0) {
        // that is., not primary name yet given, then take first plural:
        val nameInstance = createUnitNameInstance(plurals.head, "plural", false, unitInstance)
        model.add(model.createStatement(unitInstance, hasName, nameInstance))
      }
      if (plurals.length > 1) {
        stats.unitsWithMultiplePluralNames += 1
      }
    }

    // <aliases>: if present, we only consider the first occurrence.
    processAliases(_def, unitInstance, (unit \ "aliases").headOption)

    // symbols:
    for (symbol <- unit \ "symbol") {
      unitInstance.addProperty(hasSymbol, symbol.text)
    }

    stats.unitsInOutput += 1
    unitInstance
  }

  private def processAliases(_def: String, unitInstance: Resource, aliasesOpt: Option[Node]) {
    var currUnitNameInstance: Option[Resource] = None
    aliasesOpt foreach {node =>
      //println(s"\nnode = $node -- ${_def}")
      node.child foreach {
        case <name>{nameNode @ _*}</name> =>
          //println(s"nameNode -- ${nameNode}  ${nameNode.getClass}")
          nameNode foreach {
            case <singular>{singular}</singular> =>
              currUnitNameInstance = Some(createUnitNameInstance(singular.text, "singular", true, unitInstance))

            case <plural>{plural}</plural> =>
              currUnitNameInstance = Some(createUnitNameInstance(plural.text, "plural", true, unitInstance))

            case <noplural/> => // ignored

            case w if !w.isInstanceOf[Text] =>
              stats.addWarning(s"1 warning: unrecognized ${w.getClass}: {$w}")
            case w => if (w.text.length > 0)
              stats.addWarning(s"2 warning: ignored text: {$w}")
          }

        case <symbol>{symbol}</symbol> =>
          currUnitNameInstance match {
            case Some(unitNameInstance) =>
              unitNameInstance.addProperty(hasSymbol, symbol.text)
            case _ =>
              unitInstance.addProperty(hasSymbol, symbol.text)
          }

        case <noplural/> => // ignored

        case w if !w.isInstanceOf[Text] =>
          stats.addWarning(s"3 warning: unrecognized ${w.getClass}: {$w}")
        case w => if (w.text.length > 0)
          stats.addWarning(s"4 warning: ignored text: {$w}")
      }
    }
  }

  private def createUnitNameInstance(name: String, cardinality: String,
                                     isAlias: Boolean = false,
                                     unitInstance: Resource,
                                     nodeOpt: Option[Node] = None): Resource = {
    val uri = namespace + name
    val unitNameInstance = model.createResource(uri, UnitNameClass)
    model.add(model.createStatement(unitNameInstance, RDF.`type`, UnitNameClass))

    unitNameInstance.addProperty(hasCardinality, cardinality)
    unitNameInstance.addProperty(namesUnit, unitInstance)

    if (isAlias) unitInstance.addProperty(hasAlias, unitNameInstance)

    for (node <- nodeOpt; symbol <- node \ "symbol") {
      unitNameInstance.addProperty(hasSymbol, symbol.text)
    }

    stats.unitNamesInOutput += 1
    unitNameInstance
  }
}

/**
 * UDUnits prefixes to RDF converter.
 */
class PrefixConverter(xmlIn: Node, baseDefs: BaseDefs, namespace: String) extends
      Converter(xmlIn: Node, baseDefs: BaseDefs, namespace: String) {

  private object stats {
    var numPrefixesInInput = 0
    var numPrefixesInOutput = 0
    var numPrefixesWithNoName = 0

    override def toString =
      s"""  numPrefixesInInput    = $numPrefixesInInput
         |  numPrefixesInOutput   = $numPrefixesInOutput
         |  numPrefixesWithNoName = $numPrefixesWithNoName
       """.stripMargin
  }

  def convert: Model = {
    for (prefix <- xmlIn \ "prefix") {
      stats.numPrefixesInInput += 1

      val name = (prefix \ "name").text.trim
      if (name.length > 0) {
        val concept = createPrefixInstance(namespace + name)

        for (value <- prefix \ "value") {
          concept.addProperty(hasValue, value.text.trim)
        }
        for (symbol <- prefix \ "symbol") {
          concept.addProperty(hasSymbol, symbol.text.trim)
        }
      }
      else {
        stats.numPrefixesWithNoName += 1
      }
    }
    model
  }

  def getStats = stats.toString

  private def createPrefixInstance(uri: String): Resource = {
    val instance = model.createResource(uri, PrefixClass)
    model.add(model.createStatement(instance, RDF.`type`, PrefixClass))
    stats.numPrefixesInOutput += 1
    instance
  }
}

