package vct.antlr4.parser;

import java.util.ArrayList;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import vct.clang.printer.CSyntax;
import vct.col.ast.*;
import vct.col.ast.PrimitiveType.Sort;
import vct.parsers.CLexer;
import vct.parsers.CParser.AbstractDeclaratorContext;
import vct.parsers.CParser.AdditiveExpressionContext;
import vct.parsers.CParser.AlignmentSpecifierContext;
import vct.parsers.CParser.AndExpressionContext;
import vct.parsers.CParser.ArgumentExpressionListContext;
import vct.parsers.CParser.AssignmentExpressionContext;
import vct.parsers.CParser.AssignmentOperatorContext;
import vct.parsers.CParser.AtomicTypeSpecifierContext;
import vct.parsers.CParser.BlockItemContext;
import vct.parsers.CParser.BlockItemListContext;
import vct.parsers.CParser.CastExpressionContext;
import vct.parsers.CParser.CompilationUnitContext;
import vct.parsers.CParser.CompoundStatementContext;
import vct.parsers.CParser.ConditionalExpressionContext;
import vct.parsers.CParser.ConstantExpressionContext;
import vct.parsers.CParser.DeclarationContext;
import vct.parsers.CParser.DeclarationListContext;
import vct.parsers.CParser.DeclarationSpecifierContext;
import vct.parsers.CParser.DeclarationSpecifiers2Context;
import vct.parsers.CParser.DeclarationSpecifiersContext;
import vct.parsers.CParser.DeclaratorContext;
import vct.parsers.CParser.DesignationContext;
import vct.parsers.CParser.DesignatorContext;
import vct.parsers.CParser.DesignatorListContext;
import vct.parsers.CParser.DirectAbstractDeclaratorContext;
import vct.parsers.CParser.DirectDeclaratorContext;
import vct.parsers.CParser.EnumSpecifierContext;
import vct.parsers.CParser.EnumerationConstantContext;
import vct.parsers.CParser.EnumeratorContext;
import vct.parsers.CParser.EnumeratorListContext;
import vct.parsers.CParser.EqualityExpressionContext;
import vct.parsers.CParser.ExclusiveOrExpressionContext;
import vct.parsers.CParser.ExpressionContext;
import vct.parsers.CParser.ExpressionStatementContext;
import vct.parsers.CParser.ExternalDeclarationContext;
import vct.parsers.CParser.FunctionDefinitionContext;
import vct.parsers.CParser.FunctionSpecifierContext;
import vct.parsers.CParser.GccAttributeContext;
import vct.parsers.CParser.GccAttributeListContext;
import vct.parsers.CParser.GccAttributeSpecifierContext;
import vct.parsers.CParser.GccDeclaratorExtensionContext;
import vct.parsers.CParser.GenericAssocListContext;
import vct.parsers.CParser.GenericAssociationContext;
import vct.parsers.CParser.GenericSelectionContext;
import vct.parsers.CParser.IdentifierListContext;
import vct.parsers.CParser.InclusiveOrExpressionContext;
import vct.parsers.CParser.InitDeclaratorContext;
import vct.parsers.CParser.InitDeclaratorListContext;
import vct.parsers.CParser.InitializerContext;
import vct.parsers.CParser.InitializerListContext;
import vct.parsers.CParser.IterationStatementContext;
import vct.parsers.CParser.JumpStatementContext;
import vct.parsers.CParser.LabeledStatementContext;
import vct.parsers.CParser.LogicalAndExpressionContext;
import vct.parsers.CParser.LogicalOrExpressionContext;
import vct.parsers.CParser.MultiplicativeExpressionContext;
import vct.parsers.CParser.NestedParenthesesBlockContext;
import vct.parsers.CParser.ParameterDeclarationContext;
import vct.parsers.CParser.ParameterListContext;
import vct.parsers.CParser.ParameterTypeListContext;
import vct.parsers.CParser.PointerContext;
import vct.parsers.CParser.PostfixExpressionContext;
import vct.parsers.CParser.PrimaryExpressionContext;
import vct.parsers.CParser.RelationalExpressionContext;
import vct.parsers.CParser.SelectionStatementContext;
import vct.parsers.CParser.ShiftExpressionContext;
import vct.parsers.CParser.SpecificationPrimaryContext;
import vct.parsers.CParser.SpecifierQualifierListContext;
import vct.parsers.CParser.StatementContext;
import vct.parsers.CParser.StaticAssertDeclarationContext;
import vct.parsers.CParser.StorageClassSpecifierContext;
import vct.parsers.CParser.StructDeclarationContext;
import vct.parsers.CParser.StructDeclarationListContext;
import vct.parsers.CParser.StructDeclaratorContext;
import vct.parsers.CParser.StructDeclaratorListContext;
import vct.parsers.CParser.StructOrUnionContext;
import vct.parsers.CParser.StructOrUnionSpecifierContext;
import vct.parsers.CParser.TranslationUnitContext;
import vct.parsers.CParser.TypeNameContext;
import vct.parsers.CParser.TypeQualifierContext;
import vct.parsers.CParser.TypeQualifierListContext;
import vct.parsers.CParser.TypeSpecifierContext;
import vct.parsers.CParser.TypedefNameContext;
import vct.parsers.CParser.UnaryExpressionContext;
import vct.parsers.CParser.UnaryOperatorContext;
import vct.parsers.CVisitor;
import vct.util.ClassName;
import vct.util.Syntax;
import static hre.System.*;

/**
 * Convert C parse trees to COL.
 *
 * This class contains the conversions for parse tree nodes,
 * which are unique to C or have to be handled differently
 * from the same node for CML.
 * 
 * The methods in this class return null unless they need to override
 * the default behavior in ANTLRtoCOL.
 * 
 * @author <a href="mailto:s.c.c.blom@utwente.nl">Stefan Blom</a>
*/
public class CtoCOL extends AbstractCtoCOL implements CVisitor<ASTNode> {

  /**
   * Convert an ANTLR4 parse tree of a C program to a COL tree.
   * @param tree The parse tree to be converted.
   * @param file_name The file parsed.
   * @param tokens The token stream produced for the file.
   * @param parser The instance of the parser used.
   * @return COL Compilation unit with the contents of the parse tree.
   */
  public static CompilationUnit convert(ParseTree tree, String file_name,BufferedTokenStream tokens,org.antlr.v4.runtime.Parser parser) {
    // create a new compilation unit.
    CompilationUnit unit=new CompilationUnit(file_name);
    // Create a visitor that can do the conversion.
    CtoCOL visitor=new CtoCOL(CSyntax.getC(),file_name,tokens,parser);
    // Invoke the generic conversion method in ANTLRtoCOL.
    // This method will scan the parse tree for declarations
    // and put them in the compilation unit.
    visitor.scan_to(unit,tree);
    return unit;
  }

  private CtoCOL(Syntax syntax, String filename, BufferedTokenStream tokens,org.antlr.v4.runtime.Parser parser) {
    super(syntax, filename, tokens,parser,CLexer.Identifier,CLexer.class);
  }

  @Override
  public ASTNode visitAbstractDeclarator(AbstractDeclaratorContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitAdditiveExpression(AdditiveExpressionContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitAlignmentSpecifier(AlignmentSpecifierContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitAndExpression(AndExpressionContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitArgumentExpressionList(ArgumentExpressionListContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitAssignmentExpression(AssignmentExpressionContext ctx) {
    if (false && match(ctx,null,"AssignmentOperatorContext",null)){
    	ASTNode loc=convert(ctx,0);
    	ASTNode val=convert(ctx,2);
    	StandardOperator op=null;
    	AssignmentOperatorContext op_ctx=(AssignmentOperatorContext)ctx.getChild(1);
    	if (match(op_ctx,"=")){
    		op=StandardOperator.Assign;
    	}
    	if(op!=null) return create.expression(op,loc,val);
    }
    return null;
  }

  @Override
  public ASTNode visitAssignmentOperator(AssignmentOperatorContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitAtomicTypeSpecifier(AtomicTypeSpecifierContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitBlockItem(BlockItemContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitBlockItemList(BlockItemListContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitCastExpression(CastExpressionContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitCompilationUnit(CompilationUnitContext ctx) {
    return null;
  }

  @Override
  public ASTNode visitCompoundStatement(CompoundStatementContext ctx) {
    BlockStatement block=create.block();
    if (match(ctx,"{","}")) {
      scan_comments_after(block,ctx.getChild(0));
      return block;
    }
    if (!match(ctx,"{","BlockItemListContext","}")) return null;    
    doblock(block,(BlockItemListContext)ctx.getChild(1)); 
    return block;
  }
  private void doblock(BlockStatement block, BlockItemListContext ctx) {	
    if (match(ctx,"BlockItemContext")){    	      	  
    	  ASTNode temp = convert(ctx,0);
    	  scan_comments_before(block,ctx.getChild(0)); //DRB	  
    	  block.add_statement(temp);
    	  scan_comments_after(block,ctx.getChild(0));//DRB 
    } else if (match(ctx,"BlockItemListContext","BlockItemContext")){    	  	  	     	  	
    	   doblock(block,(BlockItemListContext)ctx.getChild(0));
      
    	   ASTNode temp = convert(ctx,1);    	         
    	   block.add_statement(temp);
    	   scan_comments_after(block,ctx.getChild(1)); //DRB

    } else {      
      throw hre.System.Failure("unknown BlockItemList");
    }
  }

  @Override
  public ASTNode visitConditionalExpression(ConditionalExpressionContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitConstantExpression(ConstantExpressionContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitDeclaration(DeclarationContext ctx) {
    if (match(ctx,null,";")){
      return convert(ctx,0);
    } else if (match(ctx,null,null,";")){
      VariableDeclaration res=create.variable_decl(checkType(convert(ctx,0)));
      ParserRuleContext list=(ParserRuleContext)ctx.getChild(1);
      ASTNode decls[]=convert_list(list,",");
      for(int i=0;i<decls.length;i++){
        if (decls[i] instanceof DeclarationStatement){
          res.add((DeclarationStatement)decls[i]);
        } else if (decls[i] instanceof OperatorExpression){
          OperatorExpression e=(OperatorExpression)decls[i];
          DeclarationStatement d=(DeclarationStatement)e.getArg(0);
          res.add(create.field_decl(d.getName(),d.getType(),e.getArg(1)));
        } else {
          return null;
        }
      }
      return res;
    }
    return null;
  }

  @Override
  public ASTNode visitDeclarationList(DeclarationListContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitDeclarationSpecifier(DeclarationSpecifierContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitDeclarationSpecifiers(DeclarationSpecifiersContext ctx) {
    if (match(ctx,null,null)){
      ASTNode t=convert(ctx,0);
      ASTNode v=convert(ctx,1);
      if (t instanceof Type && v instanceof NameExpression){
        return create.field_decl(((NameExpression)v).getName(), (Type)t);
      }
    }
    return null;
  }

  @Override
  public ASTNode visitDeclarationSpecifiers2(DeclarationSpecifiers2Context ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitDeclarator(DeclaratorContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitDesignation(DesignationContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitDesignator(DesignatorContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitDesignatorList(DesignatorListContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitDirectAbstractDeclarator(
      DirectAbstractDeclaratorContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitDirectDeclarator(DirectDeclaratorContext ctx) {
    return getDirectDeclarator(ctx);
  }

  @Override
  public ASTNode visitEnumerationConstant(EnumerationConstantContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitEnumerator(EnumeratorContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitEnumeratorList(EnumeratorListContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitEnumSpecifier(EnumSpecifierContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitEqualityExpression(EqualityExpressionContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitExclusiveOrExpression(ExclusiveOrExpressionContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitExpression(ExpressionContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitExpressionStatement(ExpressionStatementContext ctx) 
  {	
	if (match(ctx,"Expression",";"))
	{//DRB
      //return create.special(ASTSpecial.Kind.Expression,convert(ctx,0));
	    return convert(ctx,0);
    } 
	if (match(ctx,";")){  return create.block(); }
    return null;
  }

  @Override
  public ASTNode visitExternalDeclaration(ExternalDeclarationContext ctx) {
    return null;
  }

  @Override
  public ASTNode visitFunctionDefinition(FunctionDefinitionContext ctx) {
    int ofs=0;
    Type t=create.primitive_type(Sort.Integer);
    if (match(0,true,ctx,"DeclarationSpecifierContext")){
      ofs=1;
      t=(Type)convert(ctx,1);
    }
    ofs++;
    String name=null;
    ArrayList<DeclarationStatement> args=new ArrayList<DeclarationStatement>();
    if (match((DeclaratorContext)ctx.getChild(ofs),"DirectDeclaratorContext")){
      DirectDeclaratorContext decl_ctx=(DirectDeclaratorContext)((DeclaratorContext)ctx.getChild(ofs)).getChild(0);
      if (match(decl_ctx,null,"(","ParameterTypeListContext",")")){
        enter(decl_ctx);
        name=getIdentifier(decl_ctx, 0);
        ParserRuleContext arg_ctx=(ParserRuleContext)decl_ctx.getChild(2);
        
        if (match(arg_ctx,null,",","...")){
          throw hre.System.Failure("C varargs are not supported.");
        }
        arg_ctx=(ParserRuleContext)arg_ctx.getChild(0);
        while(match(arg_ctx,null,",",null)){
          args.add(0,(DeclarationStatement)convert(arg_ctx,2));
          arg_ctx=(ParserRuleContext)arg_ctx.getChild(0);
        }
        args.add(0,(DeclarationStatement)convert(arg_ctx));
        leave(decl_ctx,null);
      } else if (match(decl_ctx,null,"(",")")) {
        name=getIdentifier(decl_ctx, 0);
      } else {
        return null;
      }
    } else {
      throw hre.System.Failure("unknown declarator%ntree: %s",ctx.getChild(ofs).toStringTree(parser));
    }
    ofs++;
    ASTNode body;
    if (match(ofs,false,ctx,(String)null)){
      body=convert(ctx,ofs);
    } else {
      return null;
      //body=convert(ctx,ofs+1);
    }
    return create.method_decl(t, null, name, args.toArray(new DeclarationStatement[0]), body);
  }

  @Override
  public ASTNode visitFunctionSpecifier(FunctionSpecifierContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitGccAttribute(GccAttributeContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitGccAttributeList(GccAttributeListContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitGccAttributeSpecifier(GccAttributeSpecifierContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitGccDeclaratorExtension(GccDeclaratorExtensionContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitGenericAssociation(GenericAssociationContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitGenericAssocList(GenericAssocListContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitGenericSelection(GenericSelectionContext ctx) {

    return null;
  }

  @Override
  public ASTNode visitIdentifierList(IdentifierListContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitInclusiveOrExpression(InclusiveOrExpressionContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitInitDeclarator(InitDeclaratorContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitInitDeclaratorList(InitDeclaratorListContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitInitializer(InitializerContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitInitializerList(InitializerListContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitIterationStatement(IterationStatementContext ctx) {

    // TODO Auto-generated method stub		
  	if (match(ctx,"while","(",null,")",null)){ //DRB --Added		
  		  LoopStatement res=(LoopStatement)create.while_loop(convert(ctx,2),convert(ctx,4));
  	      scan_comments_after(res.get_after(), ctx.getChild(3));	      
  	      return res;
    } else if (match(ctx,"for","(",null,";",null,";",null,")",null)){ //DRB --Added    
      ASTNode body=convert(ctx,8);
      ASTNode init=convert(ctx,2);
      ASTNode test=convert(ctx,4);
      ASTNode update=convert(ctx,6);
      LoopStatement res=create.for_loop(init,test,update,body);
      scan_comments_after(res.get_after(), ctx.getChild(7));
      return res;
    } else if (match(ctx,"for","(",null,null,";",null,")",null)){ 
      ASTNode body=convert(ctx,7);
      ASTNode init=convert(ctx,2);
      init=((VariableDeclaration)init).flatten()[0];
      ASTNode test=convert(ctx,3);
      ASTNode update=convert(ctx,5);
      LoopStatement res=create.for_loop(init,test,update,body);
      scan_comments_after(res.get_after(), ctx.getChild(6));
      return res;
    }	else {
      return null;
    }
  }

  @Override
  public ASTNode visitJumpStatement(JumpStatementContext ctx) {
    if (match(ctx,"return",null,";")){
      return create.return_statement(convert(ctx,1));
    } else if (match(ctx,"return",";")){
      return create.return_statement();
    }
    return null;
  }

  @Override
  public ASTNode visitLabeledStatement(LabeledStatementContext ctx) {
    // TODO Auto-generated method stub	  	
	  if (match(ctx,null,":",null))		  
	  {//DRB			  		  
		  ASTNode res = convert(ctx,2);
		  res.addLabel(create.label(ctx.getChild(0).getText()));
		  return res; 		  		  		  	   
	  }	  
      return null;
  }

  @Override
  public ASTNode visitLogicalAndExpression(LogicalAndExpressionContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }
  
  @Override
  public ASTNode visitLogicalOrExpression(LogicalOrExpressionContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitMultiplicativeExpression(
      MultiplicativeExpressionContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitNestedParenthesesBlock(NestedParenthesesBlockContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitParameterDeclaration(ParameterDeclarationContext ctx) {
    if (match(ctx,null,null)){
      Type t=(Type)convert(ctx,0);
      ParseTree var=ctx.getChild(1);
      if (var instanceof ParserRuleContext){
        ASTNode v=convert(ctx,1);
        VariableDeclaration decl=create.variable_decl(t);
        decl.add((DeclarationStatement)v);
        DeclarationStatement vars[]=decl.flatten();
        if (vars.length==1) return vars[0];
      } else {
        String name=getIdentifier(ctx,1);
        return create.field_decl(name,t);
      }
    }
    return null;
  }

  @Override
  public ASTNode visitParameterList(ParameterListContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitParameterTypeList(ParameterTypeListContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitPointer(PointerContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitPostfixExpression(PostfixExpressionContext ctx) {
    // TODO Auto-generated method stub
    return visitPrimaryExpression((ParserRuleContext)ctx);
  }
  
  @Override
  public ASTNode visitPrimaryExpression(PrimaryExpressionContext ctx) {	   
	  return visitPrimaryExpression((ParserRuleContext)ctx);
  }
  
  @Override
  public ASTNode visitRelationalExpression(RelationalExpressionContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitSelectionStatement(SelectionStatementContext ctx) {
    // TODO Auto-generated method stub	  
	  if (match(ctx,"if","(","ExpressionContext",")",null)){	//DRB --Added  
	      return create.ifthenelse(convert(ctx,2),convert(ctx,4));
	  }
	  else if (match(ctx,"if","(","ExpressionContext",")",null,"else",null)){ //DRB --Added		  
	      return create.ifthenelse(convert(ctx,2),convert(ctx,4),convert(ctx,6));
	  }
    return null;
  }

  @Override
  public ASTNode visitShiftExpression(ShiftExpressionContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitSpecifierQualifierList(SpecifierQualifierListContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitStatement(StatementContext ctx) {
    // TODO Auto-generated method stub	 
	 /* System.out.printf("\n%s\n",ctx.getText());
	  
	  if (match(ctx,null,":",null))		  
	  {			  
		  System.out.println("sssssssssssssssssssss");
		  ASTNode A;		  
		  return null;	      
	  }*/
    return null;
  }

  @Override
  public ASTNode visitStaticAssertDeclaration(StaticAssertDeclarationContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitStorageClassSpecifier(StorageClassSpecifierContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitStructDeclaration(StructDeclarationContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitStructDeclarationList(StructDeclarationListContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitStructDeclarator(StructDeclaratorContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitStructDeclaratorList(StructDeclaratorListContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitStructOrUnion(StructOrUnionContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitStructOrUnionSpecifier(StructOrUnionSpecifierContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitTranslationUnit(TranslationUnitContext ctx) {
    return null;
  }

  @Override
  public ASTNode visitTypedefName(TypedefNameContext ctx) {
    String name=getIdentifier(ctx,0);
    return create.unresolved_name(name);
  }

  @Override
  public ASTNode visitTypeName(TypeNameContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitTypeQualifier(TypeQualifierContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitTypeQualifierList(TypeQualifierListContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitTypeSpecifier(TypeSpecifierContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitUnaryExpression(UnaryExpressionContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitUnaryOperator(UnaryOperatorContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ASTNode visitSpecificationPrimary(SpecificationPrimaryContext ctx) {
    // TODO Auto-generated method stub
    return null;
  }

}
