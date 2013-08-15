// -*- tab-width:2 ; indent-tabs-mode:nil -*-
package vct.util;

import vct.col.ast.ASTReserved;
import vct.col.ast.PrimitiveType.Sort;
import vct.col.ast.StandardOperator;
import vct.util.Syntax.Associativity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.EnumMap;

/**
 * This class decouples syntax from structure.
 * E.g. by defining a syntax, one can reuse the implementation of expression printing
 * for multiple languages.
 * 
 * @author sccblom
 *
 */
public class Syntax {

  public static enum Associativity{Left,Right,None};

  private Map<StandardOperator,Integer> precedence_map = new EnumMap<StandardOperator, Integer>(StandardOperator.class);
  private Map<StandardOperator,String[]> syntax_map = new EnumMap<StandardOperator, String[]>(StandardOperator.class);
  private Map<StandardOperator,String[]> pattern_map = new EnumMap<StandardOperator, String[]>(StandardOperator.class);
  private Map<StandardOperator,Associativity> associativity_map = new EnumMap<StandardOperator, Associativity>(StandardOperator.class);
  private Map<Sort,String> type_map = new EnumMap<Sort, String>(Sort.class);
  
  private Map<String,StandardOperator> operator_map = new HashMap<String, StandardOperator>();
  
  private Map<ASTReserved,String> reserved2syntax = new HashMap<ASTReserved, String>();
  private Map<String,ASTReserved> syntax2reserved = new HashMap<String, ASTReserved>();
  
  /**
   * Get a pattern that can be matched against an ANTLR 4.x parse tree.
   * @param op
   * @return
   */
  public String[] getPattern(StandardOperator op){
    return pattern_map.get(op);
  }
  
  /**
   * Convert a string to an operator.
   * @param op String representation of an operator. 
   * @return The standard operator the string represents, if it has been defined,
   *         and null otherwise.
   */
  public StandardOperator parseOperator(String op){
    return operator_map.get(op);
  }
  
  /** Is the standard function op an operator in this language? */
  public boolean isOperator(StandardOperator op){
    return precedence_map.get(op)!=null;
  }
  /** What is the precedence of the given operator in this language? */
  public int getPrecedence(StandardOperator op){
    return precedence_map.get(op);
  }
  /** Get the syntax for the given standard function.
      the syntax of a function is given as an array of strings,
      whose length is the arity of the functions plus 1.
      These strings have to be interleaved with the arguments.
      E.g. the Java syntax of plus is "","+","".
   */
  public String[] getSyntax(StandardOperator op){
    return syntax_map.get(op);
  }
  /** What is the associativity of the function in this language? */
  public Associativity getAssociativity(StandardOperator op){
    return associativity_map.get(op);
  }
  /** Add a left associative binary infix operator to the language. */  
  public void addLeftFix(StandardOperator op,final String syntax,int precedence){
    String full_syntax[]={"",syntax,""};
    precedence_map.put(op,precedence);
    syntax_map.put(op,full_syntax);
    operator_map.put(syntax,op);
    associativity_map.put(op,Associativity.Left);
    String pattern[]={null,syntax,null};
    pattern_map.put(op, pattern);
  }
  /** Add a non-associative binary infix operator to the language. */
  public void addInfix(StandardOperator op,final String syntax,int precedence){
    String full_syntax[]={"",syntax,""};
    precedence_map.put(op,precedence);
    syntax_map.put(op,full_syntax);
    operator_map.put(syntax,op);
    associativity_map.put(op,Associativity.None);
    String pattern[]={null,syntax,null};
    pattern_map.put(op, pattern);
  }
  /** Add a prefix operator to the language. */
  public void addPrefix(StandardOperator op,final String syntax,int precedence){
    String full_syntax[]={syntax,""};
    precedence_map.put(op,precedence);
    syntax_map.put(op,full_syntax);
    operator_map.put(syntax,op);
    associativity_map.put(op,Associativity.None);
    String pattern[]={syntax,null};
    pattern_map.put(op, pattern);
  }
  /** Add a postfix operator to the language. */
  public void addPostfix(StandardOperator op,final String syntax,int precedence){
    String full_syntax[]={"",syntax};
    precedence_map.put(op,precedence);
    syntax_map.put(op,full_syntax);
    operator_map.put(syntax,op);
    associativity_map.put(op,Associativity.None);
    String pattern[]={null,syntax};
    pattern_map.put(op, pattern);
  }

  /** Add a non-associative binary infix operator to the language. */
  public void addRightFix(StandardOperator op,final String syntax,int precedence){
    String full_syntax[]={"",syntax,""};
    precedence_map.put(op,precedence);
    syntax_map.put(op,full_syntax);
    operator_map.put(syntax,op);
    associativity_map.put(op,Associativity.Right);
    String pattern[]={null,syntax,null};
    pattern_map.put(op, pattern);
  }
  
  /** Add a standard operation that is represented with function call syntax */
  public void addFunction(StandardOperator op,final String syntax){
    int N=op.arity();
    String full_syntax[]=new String[N+1];
    String pattern[]=new String[2*N+2];
    pattern[0]=syntax;
    pattern[1]="(";
    full_syntax[0]=syntax+"(";
    for(int i=1;i<N;i++) {
      full_syntax[i]=",";
      pattern[2*i+1]=",";
    }
    full_syntax[N]=")";
    pattern[2*N+1]=")";
    syntax_map.put(op,full_syntax);
    pattern_map.put(op, pattern);
  }
  
  /** Add a name for a primitive type */
  public void addPrimitiveType(Sort sort,String name){
    type_map.put(sort, name);
  }
  
  /** Get the name of a primitive type */
  public String getPrimitiveType(Sort sort){
    return type_map.get(sort);
  }
  
  public void addOperator(StandardOperator op,int precedence,final String ... full_syntax){
    precedence_map.put(op,precedence);
    syntax_map.put(op,full_syntax);
    associativity_map.put(op,Associativity.None);
    ArrayList<String> pattern=new ArrayList<String>();
    //System.err.printf("pattern for operator %s is:",op);
    for(int i=0;i<full_syntax.length;i++){
      if (i>0) {
        //System.err.printf(" null");
        pattern.add(null);
      }
      if (full_syntax[i].length()>0) {
        //System.err.printf(" \"%s\"",full_syntax[i]);
        pattern.add(full_syntax[i]);
      }
    }
    //System.err.printf("%n");
    pattern_map.put(op, pattern.toArray(full_syntax));
  }
  
  public void addReserved(ASTReserved word,String string) {
    reserved2syntax.put(word,string);
    syntax2reserved.put(string,word);
  }
/*  
  public Iterable<String> reserved(){
    return reserved2syntax.keySet();
  }
*/
  
  public boolean is_reserved(String text) {
    return syntax2reserved.containsKey(text);
  }

  public ASTReserved reserved(String text) {
    return syntax2reserved.get(text);
  }

  public String getSyntax(ASTReserved word) {
    return reserved2syntax.get(word);
  }
}

