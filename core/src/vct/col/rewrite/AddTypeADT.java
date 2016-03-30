package vct.col.rewrite;

import hre.ast.MessageOrigin;
import vct.col.ast.*;

public class AddTypeADT extends AbstractRewriter {

  public static final String type_adt="TYPE";

  private AxiomaticDataType adt;
  
  public AddTypeADT(ProgramUnit source) {
    super(source);
    create.enter();
    create.setOrigin(new MessageOrigin("Generated type system ADT"));
    adt=create.adt(type_adt);
    adt.add_cons(create.function_decl(create.class_type(type_adt),null,"class_Object",new DeclarationStatement[0],null));
    adt.add_map(create.function_decl(
        create.primitive_type(PrimitiveType.Sort.Boolean),
        null,
        "instanceof",
        new DeclarationStatement[]{
          create.field_decl("t1",create.class_type(type_adt)),
          create.field_decl("t2",create.class_type(type_adt))
        },
        null
    ));
    adt.add_axiom(create.axiom("object_top",create.forall(
        create.constant(true),
        create.invokation(null, null,"instanceof",create.invokation(null,null,"class_Object"),create.local_name("t")),
        new DeclarationStatement[]{create.field_decl("t",create.class_type(type_adt))}
    )));
    create.leave();
    target().add(adt);
  }

  @Override
  public void visit(Method m){
    if (m.getKind()==Method.Kind.Constructor){
      currentContractBuilder=new ContractBuilder();
      currentContractBuilder.ensures(create.expression(StandardOperator.EQ,
          create.expression(StandardOperator.TypeOf,create.reserved_name(ASTReserved.Result)),
          create.invokation(create.class_type(type_adt),null,"class_"+m.name)
      ));
    }
    super.visit(m);
    if (m.getKind()==Method.Kind.Constructor){
      Method c=(Method)result;
      if (c!=null){
        ((BlockStatement)c.getBody()).prepend(create.special(ASTSpecial.Kind.Inhale,create.expression(StandardOperator.EQ,
            create.expression(StandardOperator.TypeOf,create.reserved_name(ASTReserved.This)),
            create.invokation(create.class_type(type_adt),null,"class_"+m.name)
        )));
      }
      result=c;
    }
  }

  public void visit(ASTClass cl){
    super.visit(cl);
    ASTClass res=(ASTClass)result;
    adt.add_cons(create.function_decl(create.class_type(type_adt),null,"class_"+cl.name,new DeclarationStatement[0],null));
    result=res;
  }
  
  public void visit(OperatorExpression e){
    switch(e.getOperator()){
    case Instance:
      result=create.expression(StandardOperator.EQ,
          create.expression(StandardOperator.TypeOf,rewrite(e.getArg(0))),
          create.invokation(create.class_type(type_adt),null,"class_"+e.getArg(1))
      );
      break;
    default:
      super.visit(e);
      break;
    }
  }
  
}